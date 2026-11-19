package cn.quanshu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable intent and MySQL constraints are the correctness boundary; Redis is the
 * admission/reservation layer.
 */
@Service
public class AsyncClaims {
  private static final Logger log = LoggerFactory.getLogger(AsyncClaims.class);
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final CouponService coupons;
  private final ObjectMapper json;
  private final boolean enabled;
  private static final DefaultRedisScript<String> RESERVE =
      new DefaultRedisScript<>(
          """
          local prior=redis.call('HGET',KEYS[3],ARGV[1])
          if prior then return prior end
          if redis.call('HEXISTS',KEYS[2],ARGV[2])==1 then return 'LIMIT' end
          if redis.call('EXISTS',KEYS[1])==0 then redis.call('SET',KEYS[1],ARGV[3]) end
          if tonumber(redis.call('GET',KEYS[1]))<=0 then return 'SOLD_OUT' end
          redis.call('DECR',KEYS[1])
          redis.call('HSET',KEYS[2],ARGV[2],ARGV[1])
          redis.call('HSET',KEYS[3],ARGV[1],'R')
          redis.call('ZADD',KEYS[4],ARGV[4],ARGV[1])
          return 'R'
          """,
          String.class);
  private static final DefaultRedisScript<Long> FINISH =
      new DefaultRedisScript<>(
          """
if redis.call('HGET',KEYS[3],ARGV[1])~='R' then return 0 end
if ARGV[3]=='RELEASE' then
  if redis.call('EXISTS',KEYS[1])==1 then redis.call('INCR',KEYS[1]) end
  if redis.call('HGET',KEYS[2],ARGV[2])==ARGV[1] then redis.call('HDEL',KEYS[2],ARGV[2]) end
  redis.call('HSET',KEYS[3],ARGV[1],'F')
else redis.call('HSET',KEYS[3],ARGV[1],'C') end
redis.call('ZREM',KEYS[4],ARGV[1])
return 1
""",
          Long.class);

  public AsyncClaims(
      JdbcTemplate db,
      StringRedisTemplate redis,
      CouponService coupons,
      ObjectMapper json,
      @Value("${quanshu.redis-enabled}") boolean enabled) {
    this.db = db;
    this.redis = redis;
    this.coupons = coupons;
    this.json = json;
    this.enabled = enabled;
  }

  private static List<String> keys(long template) {
    String base = "quanshu:{" + template + "}:";
    return List.of(base + "stock", base + "quota", base + "reservations", base + "pending");
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private Map<String, Object> row(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new BusinessException("领取请求不存在");
    return rows.get(0);
  }

  public Map<String, Object> result(long user, String request) {
    var r = row("SELECT * FROM claim_request WHERE request_id=?", request);
    if (n(r, "user_id") != user) throw new BusinessException("领取请求不属于当前用户");
    return r;
  }

  @Transactional
  public Map<String, Object> submit(long user, CouponService.ClaimInput in) {
    if (!enabled) throw new BusinessException("异步领取尚未启用 Redis");
    var same = db.queryForList("SELECT * FROM claim_request WHERE request_id=?", in.requestId());
    if (!same.isEmpty()) {
      var r = same.get(0);
      if (n(r, "user_id") != user || n(r, "template_id") != in.templateId())
        throw new BusinessException("请求编号已被使用");
      return r;
    }
    var old =
        db.queryForList(
            "SELECT * FROM claim_request WHERE user_id=? AND template_id=? AND state IN"
                + " ('PENDING','SUCCESS') ORDER BY created_at DESC LIMIT 1",
            user,
            in.templateId());
    if (!old.isEmpty()) return old.get(0);
    var t = row("SELECT * FROM coupon_template WHERE id=?", in.templateId());
    if (!"ACTIVE".equals(t.get("status"))
        || now() < n(t, "valid_from")
        || now() >= n(t, "valid_to")) throw new BusinessException("活动未开始或已结束");
    var owned =
        db.queryForList(
            "SELECT id FROM user_coupon WHERE user_id=? AND template_id=?", user, in.templateId());
    long time = now();
    db.update(
        "INSERT INTO claim_request(request_id,template_id,user_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?)",
        in.requestId(),
        in.templateId(),
        user,
        time,
        time);
    if (!owned.isEmpty()) {
      db.update(
          "UPDATE claim_request SET state='SUCCESS',coupon_id=? WHERE request_id=?",
          owned.get(0).get("id"),
          in.requestId());
      return result(user, in.requestId());
    }
    // On Redis loss, pending durable requests are deducted when recreating the advisory inventory.
    long pending =
        Objects.requireNonNull(
            db.queryForObject(
                "SELECT COUNT(*) FROM claim_request WHERE template_id=? AND state='PENDING' AND"
                    + " request_id<>?",
                Long.class,
                in.templateId(),
                in.requestId()));
    String reserved =
        redis.execute(
            RESERVE,
            keys(in.templateId()),
            in.requestId(),
            Long.toString(user),
            Long.toString(Math.max(0, n(t, "remaining_stock") - pending)),
            Long.toString(time));
    if (!"R".equals(reserved)) {
      db.update(
          "UPDATE claim_request SET state='FAILED',error=? WHERE request_id=?",
          "LIMIT".equals(reserved) ? "已达到领取限制" : "库存不足",
          in.requestId());
    } else {
      try {
        db.update(
            "INSERT INTO outbox(id,kind,payload,next_attempt,created_at)"
                + " VALUES(?,'ASYNC_CLAIM',?,?,?)",
            UUID.randomUUID().toString(),
            json.writeValueAsString(Map.of("requestId", in.requestId())),
            time,
            time);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return result(user, in.requestId());
  }

  private void finish(long template, long user, String request, boolean release) {
    try {
      redis.execute(
          FINISH, keys(template), request, Long.toString(user), release ? "RELEASE" : "COMMIT");
    } catch (RuntimeException e) {
      log.warn(
          "Reservation finalization deferred: template={} request={} cause={}",
          template,
          request,
          e.getClass().getSimpleName());
    }
  }

  private void afterCommit(long template, long user, String request, boolean release) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            finish(template, user, request, release);
          }
        });
  }

  @Transactional
  public void consume(String event, String request) {
    if (!db.queryForList("SELECT event_id FROM inbox WHERE event_id=?", event).isEmpty()) return;
    var r = row("SELECT * FROM claim_request WHERE request_id=? FOR UPDATE", request);
    if ("PENDING".equals(r.get("state"))) {
      long user = n(r, "user_id"), template = n(r, "template_id");
      try {
        var coupon = coupons.claim(user, new CouponService.ClaimInput(template, request));
        boolean issued = request.equals(coupon.get("request_id"));
        db.update(
            "UPDATE claim_request SET state='SUCCESS',coupon_id=?,issued_new=?,updated_at=? WHERE"
                + " request_id=?",
            coupon.get("id"),
            issued,
            now(),
            request);
        afterCommit(template, user, request, !issued);
      } catch (BusinessException e) {
        db.update(
            "UPDATE claim_request SET state='FAILED',error=?,updated_at=? WHERE request_id=?",
            e.getMessage(),
            now(),
            request);
        afterCommit(template, user, request, true);
      }
    }
    db.update("INSERT INTO inbox(event_id,processed_at) VALUES(?,?)", event, now());
  }

  @Transactional
  public void repair() {
    if (!enabled) return;
    for (var r :
        db.queryForList(
            "SELECT * FROM claim_request WHERE state='PENDING' AND updated_at<? ORDER BY updated_at"
                + " LIMIT 100 FOR UPDATE SKIP LOCKED",
            now() - 60000)) {
      String request = r.get("request_id").toString();
      if (n(r, "retries") >= 5) {
        db.update(
            "UPDATE claim_request SET state='FAILED',error='处理超时，已释放预占',updated_at=? WHERE"
                + " request_id=? AND state='PENDING'",
            now(),
            request);
        afterCommit(n(r, "template_id"), n(r, "user_id"), request, true);
      } else {
        db.update(
            "UPDATE outbox SET state='PENDING',attempts=0,next_attempt=? WHERE kind='ASYNC_CLAIM'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.requestId'))=? AND state IN"
                + " ('SENT','FAILED')",
            now(),
            request);
        db.update(
            "UPDATE claim_request SET retries=retries+1,updated_at=? WHERE request_id=?",
            now(),
            request);
      }
    }
  }

  public void reconcileReservations() {
    if (!enabled) return;
    for (var t : db.queryForList("SELECT id FROM coupon_template")) {
      long template = n(t, "id");
      var overdue =
          redis.opsForZSet().rangeByScore(keys(template).get(3), 0, now() - 60000, 0, 100);
      if (overdue == null) continue;
      for (String request : overdue) {
        var records = db.queryForList("SELECT * FROM claim_request WHERE request_id=?", request);
        if (records.isEmpty()) {
          // Orphaned reservation after a rolled-back API transaction. Recover its user from quota
          // hash.
          for (var quota : redis.opsForHash().entries(keys(template).get(1)).entrySet())
            if (request.equals(quota.getValue())) {
              finish(template, Long.parseLong(quota.getKey().toString()), request, true);
              break;
            }
        } else {
          var r = records.get(0);
          String state = r.get("state").toString();
          Object issued = r.get("issued_new");
          boolean issuedNew =
              Boolean.TRUE.equals(issued)
                  || (issued instanceof Number value && value.intValue() == 1);
          if (!"PENDING".equals(state))
            finish(template, n(r, "user_id"), request, "FAILED".equals(state) || !issuedNew);
        }
      }
    }
  }
}
