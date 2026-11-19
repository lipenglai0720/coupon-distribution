package cn.quanshu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ReadCaches cache;
  private final DiscountCalculator discounts;

  public CouponService(
      JdbcTemplate db, ObjectMapper json, ReadCaches cache, DiscountCalculator discounts) {
    this.db = db;
    this.json = json;
    this.cache = cache;
    this.discounts = discounts;
  }

  public record TemplateInput(
      @Positive long merchantId,
      @NotBlank @Size(max = 100) String name,
      @Min(1) @Max(10000000) int stock,
      @Min(0) long thresholdCents,
      @Positive long discountCents,
      @Positive long validFrom,
      @Positive long validTo) {}

  public record ClaimInput(
      @Positive long templateId, @NotBlank @Size(max = 100) String requestId) {}

  public record OrderInput(
      @NotBlank @Size(max = 100) String orderId,
      @Positive long merchantId,
      @Positive long amountCents) {}

  public record TaskInput(
      @Positive long templateId, @NotEmpty @Size(max = 5000) List<@Positive Long> userIds) {}

  private static long now() {
    return System.currentTimeMillis();
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new BusinessException("记录不存在");
    return rows.get(0);
  }

  @Transactional
  public long create(TemplateInput in) {
    cache.beforeTemplateCreate();
    if (in.validTo <= in.validFrom || in.validTo <= now()) throw new BusinessException("有效期不正确");
    if (in.discountCents > in.thresholdCents && in.thresholdCents != 0)
      throw new BusinessException("优惠金额不能超过门槛");
    long id =
        number(one("SELECT next_id FROM id_allocator WHERE name='template' FOR UPDATE"), "next_id");
    db.update("UPDATE id_allocator SET next_id=next_id+1 WHERE name='template'");
    db.update(
        "INSERT INTO"
            + " coupon_template(id,merchant_id,name,total_stock,remaining_stock,threshold_cents,discount_cents,valid_from,valid_to)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        in.merchantId,
        in.name,
        in.stock,
        in.stock,
        in.thresholdCents,
        in.discountCents,
        in.validFrom,
        in.validTo);
    cache.templateCreated(id);
    emit("TEMPLATE_EXPIRE", Map.of("templateId", id, "deliveryAt", in.validTo));
    return id;
  }

  public List<Map<String, Object>> templates() {
    return db.queryForList("SELECT * FROM coupon_template ORDER BY id DESC LIMIT 200");
  }

  @Transactional
  public void end(long id) {
    one("SELECT id FROM coupon_template WHERE id=? FOR UPDATE", id);
    db.update("UPDATE coupon_template SET status='ENDED' WHERE id=?", id);
    cache.templateChanged(id);
  }

  @Transactional
  public void expireTemplates() {
    for (long id :
        db.queryForList(
            "SELECT id FROM coupon_template WHERE status='ACTIVE' AND valid_to<=? LIMIT 200",
            Long.class,
            now())) end(id);
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public Map<String, Object> claim(long user, ClaimInput in) {
    db.update(
        "INSERT INTO request_identity(request_id,user_id,template_id) VALUES(?,?,?) ON DUPLICATE"
            + " KEY UPDATE request_id=request_id",
        in.requestId,
        user,
        in.templateId);
    var identity =
        one(
            "SELECT user_id,template_id FROM request_identity WHERE request_id=? FOR UPDATE",
            in.requestId);
    if (number(identity, "user_id") != user || number(identity, "template_id") != in.templateId)
      throw new BusinessException("请求编号已被其他领取使用");
    var t = one("SELECT * FROM coupon_template WHERE id=? FOR UPDATE", in.templateId);
    var existingRequest =
        db.queryForList(
            "SELECT * FROM user_coupon WHERE user_id=? AND request_id=?", user, in.requestId);
    if (!existingRequest.isEmpty()) {
      var c = existingRequest.get(0);
      if (number(c, "user_id") != user || number(c, "template_id") != in.templateId)
        throw new BusinessException("请求编号已被其他领取使用");
      return c;
    }
    var existing =
        db.queryForList(
            "SELECT * FROM user_coupon WHERE user_id=? AND template_id=?", user, in.templateId);
    if (!existing.isEmpty()) return existing.get(0);
    if (!"ACTIVE".equals(t.get("status"))
        || now() < number(t, "valid_from")
        || now() >= number(t, "valid_to")) throw new BusinessException("活动未开始或已结束");
    if (number(t, "remaining_stock") < 1) throw new BusinessException("库存不足");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO user_coupon(id,template_id,user_id,request_id,status,created_at)"
            + " VALUES(?,?,?,?,'AVAILABLE',?)",
        id,
        in.templateId,
        user,
        in.requestId,
        now());
    db.update(
        "UPDATE coupon_template SET remaining_stock=remaining_stock-1 WHERE id=? AND"
            + " remaining_stock>0",
        in.templateId);
    emit("COUPON_GRANTED", Map.of("couponId", id, "userId", user));
    emit(
        "COUPON_EXPIRE",
        Map.of("couponId", id, "userId", user, "deliveryAt", number(t, "valid_to")));
    cache.walletChanged(user);
    return one("SELECT * FROM user_coupon WHERE user_id=? AND id=?", user, id);
  }

  public List<Map<String, Object>> wallet(long user) {
    return cache.wallet(user).stream().limit(500).toList();
  }

  public List<Map<String, Object>> usable(long user, long merchant, long amount) {
    return discounts.calculate(cache.wallet(user), merchant, amount, now());
  }

  private Map<String, Object> owned(long user, String id) {
    cache.lockWallet(user);
    var c = one("SELECT * FROM user_coupon WHERE user_id=? AND id=? FOR UPDATE", user, id);
    if (number(c, "user_id") != user) throw new BusinessException("优惠券不属于当前用户");
    return c;
  }

  private boolean already(String id, String order, String operation) {
    var ops =
        db.queryForList(
            "SELECT coupon_id FROM coupon_operation WHERE order_id=? AND operation=?",
            order,
            operation);
    if (ops.isEmpty()) return false;
    if (!id.equals(ops.get(0).get("coupon_id"))) throw new BusinessException("订单已关联另一张券");
    return true;
  }

  private void record(String id, String order, String operation) {
    db.update(
        "INSERT INTO coupon_operation(coupon_id,order_id,operation,created_at) VALUES(?,?,?,?)",
        id,
        order,
        operation,
        now());
  }

  @Transactional
  public Map<String, Object> lock(long user, String id, OrderInput in) {
    var c = owned(user, id);
    db.update(
        "INSERT INTO order_identity(order_id,user_id,coupon_id) VALUES(?,?,?) ON DUPLICATE KEY"
            + " UPDATE order_id=order_id",
        in.orderId,
        user,
        id);
    var order =
        one("SELECT user_id,coupon_id FROM order_identity WHERE order_id=? FOR UPDATE", in.orderId);
    if (number(order, "user_id") != user || !id.equals(order.get("coupon_id")))
      throw new BusinessException("订单已关联另一张券");
    if (already(id, in.orderId, "LOCK")) return c;
    var t = one("SELECT * FROM coupon_template WHERE id=?", c.get("template_id"));
    if (!"AVAILABLE".equals(c.get("status"))
        || now() < number(t, "valid_from")
        || now() >= number(t, "valid_to")) throw new BusinessException("优惠券不可用");
    if (number(t, "merchant_id") != in.merchantId || in.amountCents < number(t, "threshold_cents"))
      throw new BusinessException("订单不满足使用条件");
    db.update(
        "UPDATE user_coupon SET status='LOCKED',order_id=?,lock_until=? WHERE user_id=? AND id=?"
            + " AND status='AVAILABLE'",
        in.orderId,
        Math.min(now() + 900000, number(t, "valid_to")),
        user,
        id);
    record(id, in.orderId, "LOCK");
    emit(
        "LOCK_EXPIRE",
        Map.of(
            "couponId",
            id,
            "userId",
            user,
            "orderId",
            in.orderId,
            "deliveryAt",
            Math.min(now() + 900000, number(t, "valid_to"))));
    cache.walletChanged(user);
    return one("SELECT * FROM user_coupon WHERE user_id=? AND id=?", user, id);
  }

  @Transactional
  public Map<String, Object> transition(long user, String id, String order, String action) {
    return transition(user, id, order, action, null);
  }

  @Transactional
  public Map<String, Object> transitionForMerchant(
      long merchant, long user, String id, String order, String action) {
    return transition(user, id, order, action, merchant);
  }

  private Map<String, Object> transition(
      long user, String id, String order, String action, Long merchant) {
    if (!Set.of("REDEEM", "REFUND", "UNLOCK").contains(action)) throw new BusinessException("未知操作");
    var c = owned(user, id);
    var t =
        one("SELECT valid_to,merchant_id FROM coupon_template WHERE id=?", c.get("template_id"));
    if (merchant != null && merchant != number(t, "merchant_id"))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    if (already(id, order, action)) return c;
    if (!order.equals(c.get("order_id"))) throw new BusinessException("订单与优惠券不匹配");
    boolean expired = now() >= number(t, "valid_to");
    String target;
    if ("REDEEM".equals(action)) {
      if (!"LOCKED".equals(c.get("status")) || now() >= number(c, "lock_until") || expired)
        throw new BusinessException("锁券已超时或状态不正确");
      target = "USED";
    } else {
      String required = "REFUND".equals(action) ? "USED" : "LOCKED";
      if (!required.equals(c.get("status"))) throw new BusinessException("当前状态不能执行此操作");
      target = expired ? "EXPIRED" : "AVAILABLE";
    }
    db.update(
        "UPDATE user_coupon SET status=?,order_id=?,lock_until=NULL WHERE user_id=? AND id=?",
        target,
        "USED".equals(target) ? order : null,
        user,
        id);
    record(id, order, action);
    cache.walletChanged(user);
    return one("SELECT * FROM user_coupon WHERE user_id=? AND id=?", user, id);
  }

  @Transactional
  public void expire() {
    long time = now();
    var affected =
        db.queryForList(
            "SELECT c.id,c.user_id,c.status,c.lock_until,t.valid_to FROM user_coupon c JOIN"
                + " coupon_template t ON t.id=c.template_id WHERE (c.status='LOCKED' AND"
                + " c.lock_until<=?) OR (c.status='AVAILABLE' AND t.valid_to<=?) LIMIT 500",
            time,
            time);
    for (var row : affected) {
      long user = number(row, "user_id");
      String target = number(row, "valid_to") <= time ? "EXPIRED" : "AVAILABLE";
      int changed =
          db.update(
              "UPDATE user_coupon SET status=?,order_id=NULL,lock_until=NULL WHERE user_id=? AND"
                  + " id=? AND status=? AND (status='AVAILABLE' OR lock_until<=?)",
              target,
              user,
              row.get("id"),
              row.get("status"),
              time);
      if (changed > 0) cache.walletChanged(user);
    }
  }

  private void emit(String kind, Map<String, ?> payload) {
    try {
      long time = now();
      db.update(
          "INSERT INTO outbox(id,kind,payload,next_attempt,created_at) VALUES(?,?,?,?,?)",
          UUID.randomUUID().toString(),
          kind,
          json.writeValueAsString(payload),
          time,
          time);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void expireEvent(String kind, Map<String, Object> payload) {
    if (number(payload, "deliveryAt") > now()) {
      emit(kind, payload);
      return;
    }
    if ("TEMPLATE_EXPIRE".equals(kind)) {
      long template = number(payload, "templateId");
      if (db.update(
              "UPDATE coupon_template SET status='ENDED' WHERE id=? AND status='ACTIVE' AND"
                  + " valid_to<=?",
              template,
              now())
          > 0) cache.templateChanged(template);
      return;
    }
    long user = number(payload, "userId");
    String id = payload.get("couponId").toString();
    var rows =
        db.queryForList(
            "SELECT c.*,t.valid_to FROM user_coupon c JOIN coupon_template t ON t.id=c.template_id"
                + " WHERE c.user_id=? AND c.id=? FOR UPDATE",
            user,
            id);
    if (rows.isEmpty()) return;
    var c = rows.get(0);
    long time = now();
    boolean expired = number(c, "valid_to") <= time;
    boolean unlock =
        "LOCK_EXPIRE".equals(kind)
            && "LOCKED".equals(c.get("status"))
            && Objects.equals(c.get("order_id"), payload.get("orderId"))
            && number(c, "lock_until") <= time;
    if ((expired && Set.of("AVAILABLE", "LOCKED").contains(c.get("status"))) || unlock) {
      db.update(
          "UPDATE user_coupon SET status=?,order_id=NULL,lock_until=NULL WHERE user_id=? AND id=?",
          expired ? "EXPIRED" : "AVAILABLE",
          user,
          id);
      cache.walletChanged(user);
    }
  }

  @Transactional
  public String distribute(TaskInput in) {
    one("SELECT id FROM coupon_template WHERE id=?", in.templateId);
    String task = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO distribution_task(id,template_id,created_at) VALUES(?,?,?)",
        task,
        in.templateId,
        now());
    for (long user : new LinkedHashSet<>(in.userIds)) {
      db.update("INSERT INTO distribution_recipient(task_id,user_id) VALUES(?,?)", task, user);
      emit("BATCH_CLAIM", Map.of("taskId", task, "templateId", in.templateId, "userId", user));
    }
    return task;
  }

  public Map<String, Object> task(String id) {
    var result = new LinkedHashMap<>(one("SELECT * FROM distribution_task WHERE id=?", id));
    result.put(
        "recipients",
        db.queryForList(
            "SELECT user_id,state,error FROM distribution_recipient WHERE task_id=? ORDER BY"
                + " user_id LIMIT 200",
            id));
    var counts = new LinkedHashMap<String, Long>();
    for (String state : List.of("PENDING", "SUCCESS", "FAILED")) counts.put(state, 0L);
    for (var row :
        db.queryForList(
            "SELECT state,COUNT(*) AS n FROM distribution_recipient WHERE task_id=? GROUP BY state",
            id)) counts.put(row.get("state").toString(), number(row, "n"));
    result.put("counts", counts);
    result.put("recipientLimit", 200);
    long importErrors =
        Objects.requireNonNull(
            db.queryForObject(
                "SELECT COUNT(*) FROM distribution_import_error WHERE task_id=?", Long.class, id));
    result.put("importErrors", importErrors);
    result.put(
        "errors",
        db.queryForList(
            "SELECT source_row,error FROM distribution_import_error WHERE task_id=? ORDER BY"
                + " source_row LIMIT 200",
            id));
    String parse = result.get("parse_state").toString();
    result.put(
        "status",
        "FAILED".equals(parse)
            ? "PARSE_FAILED"
            : !"COMPLETE".equals(parse) || counts.get("PENDING") > 0
                ? "PROCESSING"
                : counts.get("FAILED") > 0 || importErrors > 0
                    ? "COMPLETED_WITH_FAILURES"
                    : "COMPLETED");
    return result;
  }

  @Transactional
  public void consume(String eventId, String kind, Map<String, Object> payload) {
    if (!db.queryForList("SELECT event_id FROM inbox WHERE event_id=?", eventId).isEmpty()) return;
    db.update("INSERT INTO inbox(event_id,processed_at) VALUES(?,?)", eventId, now());
    if ("BATCH_CLAIM".equals(kind)) {
      distributeTo(
          payload.get("taskId").toString(),
          number(payload, "templateId"),
          number(payload, "userId"));
    } else if ("BATCH_CLAIM_GROUP".equals(kind)) {
      if (!(payload.get("userIds") instanceof List<?> users)
          || users.isEmpty()
          || users.size() > FileDistribution.DELIVERY_BATCH_SIZE
          || users.stream().anyMatch(value -> !(value instanceof Number n) || n.longValue() < 1))
        throw new BusinessException("无效的批量发券消息");
      String task = payload.get("taskId").toString();
      long template = number(payload, "templateId");
      for (var user : users) distributeTo(task, template, ((Number) user).longValue());
    } else if (Set.of("TEMPLATE_EXPIRE", "COUPON_EXPIRE", "LOCK_EXPIRE").contains(kind))
      expireEvent(kind, payload);
    else if (!"COUPON_GRANTED".equals(kind)) throw new BusinessException("未知事件类型");
  }

  private void distributeTo(String task, long template, long user) {
    try {
      claim(user, new ClaimInput(template, task + ":" + user));
      db.update(
          "UPDATE distribution_recipient SET state='SUCCESS',error=NULL WHERE task_id=? AND"
              + " user_id=?",
          task,
          user);
    } catch (BusinessException e) {
      db.update(
          "UPDATE distribution_recipient SET state='FAILED',error=? WHERE task_id=? AND user_id=?",
          e.getMessage(),
          task,
          user);
    }
  }
}
