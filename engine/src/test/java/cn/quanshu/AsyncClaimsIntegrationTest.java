package cn.quanshu;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:13306/quanshu_test?serverTimezone=UTC}",
      "quanshu.redis-enabled=true",
      "quanshu.jobs-enabled=false",
      "quanshu.mq-enabled=false",
      "spring.data.redis.host=127.0.0.1",
      "spring.data.redis.port=${TEST_REDIS_PORT:16379}",
      "spring.data.redis.database=15"
    })
class AsyncClaimsIntegrationTest {
  @Autowired AsyncClaims async;
  @Autowired CouponService coupons;
  @Autowired JdbcTemplate db;
  @Autowired StringRedisTemplate redis;
  @Autowired PlatformTransactionManager transactions;
  private final List<Long> templates = new ArrayList<>();

  @BeforeEach
  void reset() throws Exception {
    try (var c = Objects.requireNonNull(db.getDataSource()).getConnection()) {
      assertThat(c.getMetaData().getURL()).contains("/quanshu_test?");
    }
    try (var connection = Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
      assertThat(connection.ping()).isEqualTo("PONG");
    }
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    for (String table :
        List.of(
            "request_identity",
            "order_identity",
            "claim_request",
            "distribution_import_error",
            "distribution_recipient",
            "distribution_task",
            "inbox",
            "outbox",
            "coupon_operation",
            "user_coupon",
            "coupon_template")) db.update("DELETE FROM " + table);
  }

  List<String> keys(long template) {
    String p = "quanshu:{" + template + "}:";
    return List.of(p + "stock", p + "quota", p + "reservations", p + "pending");
  }

  @AfterEach
  void cleanup() {
    for (long t : templates) redis.delete(keys(t));
    templates.clear();
  }

  long template(int stock) {
    long time = System.currentTimeMillis();
    long id =
        coupons.create(
            new CouponService.TemplateInput(
                1, "Redis验收", stock, 10000, 2000, time - 1000, time + 3600000));
    templates.add(id);
    return id;
  }

  Map<String, Object> submit(long user, long template, String request) {
    return async.submit(user, new CouponService.ClaimInput(template, request));
  }

  @Test
  void reserveThenConsumeAndDeduplicate() {
    long t = template(1);
    assertThat(submit(1, t, "async-1").get("state")).isEqualTo("PENDING");
    assertThat(coupons.wallet(1)).isEmpty();
    assertThat(redis.opsForValue().get(keys(t).get(0))).isEqualTo("0");
    async.consume("message-1", "async-1");
    async.consume("message-1", "async-1");
    assertThat(async.result(1, "async-1").get("state")).isEqualTo("SUCCESS");
    assertThat(coupons.wallet(1)).hasSize(1);
    assertThat(submit(1, t, "async-other").get("request_id")).isEqualTo("async-1");
    assertThatThrownBy(() -> async.result(2, "async-1")).isInstanceOf(BusinessException.class);
  }

  @Test
  void businessRejectionReleasesReservationExactlyOnce() {
    long t = template(1);
    submit(1, t, "async-end");
    coupons.end(t);
    async.consume("message-end", "async-end");
    async.consume("message-end-again", "async-end");
    assertThat(async.result(1, "async-end").get("state")).isEqualTo("FAILED");
    assertThat(redis.opsForValue().get(keys(t).get(0))).isEqualTo("1");
    assertThat(coupons.wallet(1)).isEmpty();
  }

  @Test
  void rollbackAfterRedisReservationIsReconciled() {
    long t = template(1);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          submit(1, t, "orphan");
                          throw new IllegalStateException("injected post-reservation failure");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM claim_request WHERE request_id='orphan'", Long.class))
        .isZero();
    redis.opsForZSet().add(keys(t).get(3), "orphan", 0);
    async.reconcileReservations();
    async.reconcileReservations();
    assertThat(redis.opsForValue().get(keys(t).get(0))).isEqualTo("1");
  }

  @Test
  void exhaustedTimeoutIgnoresLateMessages() {
    long t = template(1);
    submit(1, t, "timeout");
    db.update("UPDATE claim_request SET retries=5,updated_at=0 WHERE request_id='timeout'");
    async.repair();
    async.repair();
    async.consume("late", "timeout");
    assertThat(async.result(1, "timeout").get("state")).isEqualTo("FAILED");
    assertThat(redis.opsForValue().get(keys(t).get(0))).isEqualTo("1");
    assertThat(coupons.wallet(1)).isEmpty();
  }

  @Test
  void redisLossRebuildAccountsForPendingIntents() {
    long t = template(2);
    submit(1, t, "loss-1");
    redis.delete(keys(t));
    submit(2, t, "loss-2");
    assertThat(submit(3, t, "loss-3").get("state")).isEqualTo("FAILED");
    async.consume("loss-event-2", "loss-2");
    async.consume("loss-event-1", "loss-1");
    assertThat(coupons.wallet(1)).hasSize(1);
    assertThat(coupons.wallet(2)).hasSize(1);
    assertThat(
            db.queryForObject(
                "SELECT remaining_stock FROM coupon_template WHERE id=?", Integer.class, t))
        .isZero();
  }

  @Test
  void concurrentAsyncAdmissionDoesNotOversell() throws Exception {
    long t = template(3);
    var pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<String>> jobs = new ArrayList<>();
      for (int i = 1; i <= 20; i++) {
        int user = i;
        jobs.add(() -> submit(user, t, "concurrent-" + user).get("state").toString());
      }
      long accepted = 0;
      for (var f : pool.invokeAll(jobs)) if ("PENDING".equals(f.get())) accepted++;
      assertThat(accepted).isEqualTo(3);
      for (var request :
          db.queryForList("SELECT request_id FROM claim_request WHERE state='PENDING'"))
        async.consume(UUID.randomUUID().toString(), request.get("request_id").toString());
      assertThat(db.queryForObject("SELECT COUNT(*) FROM user_coupon", Long.class)).isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
  }
}
