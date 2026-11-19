package cn.quanshu;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:13306/quanshu_test?serverTimezone=UTC}",
      "quanshu.mq-enabled=false",
      "quanshu.redis-enabled=false",
      "quanshu.demo-mode=true",
      "quanshu.jobs-enabled=false"
    })
class CouponIntegrationTest {
  @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
  @Autowired CouponService service;
  @Autowired JdbcTemplate db;
  @Autowired MessageRecovery messageRecovery;

  @BeforeEach
  void reset() throws Exception {
    try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
      assertThat(connection.getMetaData().getURL()).contains("/quanshu_test?");
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

  long template(int stock) {
    long now = System.currentTimeMillis();
    return service.create(
        new CouponService.TemplateInput(
            1, "满100减20", stock, 10000, 2000, now - 1000, now + 3600000));
  }

  String claim(long user, long template) {
    return service
        .claim(user, new CouponService.ClaimInput(template, UUID.randomUUID().toString()))
        .get("id")
        .toString();
  }

  @Test
  void concurrentClaimNeverOversells() throws Exception {
    long t = template(20);
    var pool = Executors.newFixedThreadPool(16);
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int i = 1; i <= 100; i++) {
        long user = i;
        jobs.add(
            () -> {
              try {
                claim(user, t);
                return true;
              } catch (BusinessException e) {
                return false;
              }
            });
      }
      long successes = 0;
      for (var f : pool.invokeAll(jobs)) if (f.get()) successes++;
      assertThat(successes).isEqualTo(20);
      assertThat(
              db.queryForObject(
                  "SELECT remaining_stock FROM coupon_template WHERE id=?", Integer.class, t))
          .isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM user_coupon", Long.class)).isEqualTo(20);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void duplicateUserAndRequestAreIdempotent() {
    long t = template(5);
    var input = new CouponService.ClaimInput(t, "request-1");
    String id = service.claim(1, input).get("id").toString();
    assertThat(service.claim(1, input).get("id")).isEqualTo(id);
    assertThat(claim(1, t)).isEqualTo(id);
    assertThatThrownBy(() -> service.claim(2, input)).isInstanceOf(BusinessException.class);
    assertThat(
            db.queryForObject(
                "SELECT remaining_stock FROM coupon_template WHERE id=?", Integer.class, t))
        .isEqualTo(4);
  }

  @Test
  void lockRedeemRefundAndLateEvents() {
    String id = claim(1, template(3));
    assertThat(service.usable(1, 1, 10000)).hasSize(1);
    service.lock(1, id, new CouponService.OrderInput("order-A", 1, 10000));
    assertThatThrownBy(() -> service.lock(1, id, new CouponService.OrderInput("order-B", 1, 10000)))
        .isInstanceOf(BusinessException.class);
    service.transition(1, id, "order-A", "REDEEM");
    service.transition(1, id, "order-A", "REDEEM");
    service.transition(1, id, "order-A", "REFUND");
    service.lock(1, id, new CouponService.OrderInput("order-B", 1, 10000));
    service.transition(1, id, "order-A", "REFUND"); // Duplicate old refund must not release B.
    assertThatThrownBy(() -> service.transition(1, id, "order-A", "UNLOCK"))
        .isInstanceOf(BusinessException.class);
    assertThat(service.wallet(1).get(0).get("status")).isEqualTo("LOCKED");
    assertThat(service.wallet(1).get(0).get("order_id")).isEqualTo("order-B");
  }

  @Test
  void ownershipThresholdAndMerchantAreEnforced() {
    String id = claim(1, template(2));
    assertThatThrownBy(() -> service.lock(2, id, new CouponService.OrderInput("A", 1, 10000)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.lock(1, id, new CouponService.OrderInput("A", 2, 10000)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.lock(1, id, new CouponService.OrderInput("A", 1, 9999)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void expiredLocksCannotBeRedeemedAndAreReleased() {
    String id = claim(1, template(2));
    service.lock(1, id, new CouponService.OrderInput("A", 1, 10000));
    db.update("UPDATE user_coupon SET lock_until=? WHERE id=?", System.currentTimeMillis() - 1, id);
    assertThatThrownBy(() -> service.transition(1, id, "A", "REDEEM"))
        .isInstanceOf(BusinessException.class);
    service.expire();
    assertThat(service.wallet(1).get(0).get("status")).isEqualTo("AVAILABLE");
  }

  @Test
  void endingTemplateStopsNewClaimsButPreservesIssuedCoupons() {
    long t = template(2);
    String id = claim(1, t);
    service.end(t);
    assertThatThrownBy(() -> claim(2, t)).isInstanceOf(BusinessException.class);
    assertThat(service.lock(1, id, new CouponService.OrderInput("A", 1, 10000)).get("status"))
        .isEqualTo("LOCKED");
  }

  @Test
  void batchMessagesAreDurableAndConsumeExactlyOnceAtBusinessBoundary() {
    long t = template(1);
    String task = service.distribute(new CouponService.TaskInput(t, List.of(1L, 1L, 2L)));
    assertThat((List<?>) service.task(task).get("recipients")).hasSize(2);
    var payload = Map.<String, Object>of("taskId", task, "templateId", t, "userId", 1L);
    service.consume("event-1", "BATCH_CLAIM", payload);
    service.consume("event-1", "BATCH_CLAIM", payload);
    service.consume(
        "event-2", "BATCH_CLAIM", Map.of("taskId", task, "templateId", t, "userId", 2L));
    assertThat(service.wallet(1)).hasSize(1);
    assertThat(service.wallet(2)).isEmpty();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM distribution_recipient WHERE state='FAILED'", Long.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE state='PENDING' AND kind IN"
                    + " ('BATCH_CLAIM','COUPON_GRANTED')",
                Long.class))
        .isEqualTo(3);
  }

  @Test
  void httpValidationAndCsvImport() {
    long t = template(10);
    var invalid =
        http.postForEntity(
            "/api/tasks", Map.of("templateId", t, "userIds", List.of(-1)), String.class);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
    body.add("templateId", t);
    body.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(
            "user_id\n11\n12\n11\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return "users.csv";
          }
        });
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
    var imported =
        http.postForEntity(
            "/api/tasks/import",
            new org.springframework.http.HttpEntity<>(body, headers),
            Map.class);
    assertThat(imported.getStatusCode().value()).isEqualTo(200);
    assertThat(
            (List<?>)
                service
                    .task(Objects.requireNonNull(imported.getBody()).get("id").toString())
                    .get("recipients"))
        .hasSize(2);
  }

  @Test
  void failedMessageRollsBackInbox() {
    assertThatThrownBy(() -> service.consume("bad-event", "UNKNOWN", Map.of()))
        .isInstanceOf(BusinessException.class);
    assertThat(
            db.queryForObject("SELECT COUNT(*) FROM inbox WHERE event_id='bad-event'", Long.class))
        .isZero();
  }

  @Test
  void recoversUnacknowledgedMessagesWithoutReplayingFutureDelayedMessages() {
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO outbox(id,kind,payload,state,next_attempt,created_at)"
            + " VALUES('lost-ack','COUPON_GRANTED','{}','SENT',0,?)",
        now);
    db.update(
        "INSERT INTO outbox(id,kind,payload,state,next_attempt,created_at)"
            + " VALUES('future-delay','COUPON_EXPIRE','{}','SENT',?,?)",
        now + 3600000,
        now);
    messageRecovery.repair();
    assertThat(db.queryForObject("SELECT state FROM outbox WHERE id='lost-ack'", String.class))
        .isEqualTo("PENDING");
    assertThat(db.queryForObject("SELECT attempts FROM outbox WHERE id='lost-ack'", Integer.class))
        .isEqualTo(1);
    assertThat(db.queryForObject("SELECT state FROM outbox WHERE id='future-delay'", String.class))
        .isEqualTo("SENT");
  }

  @Test
  void delayedExpiryIgnoresOldOrderAndExpiresOnlyCurrentLock() {
    long t = template(2);
    String id = claim(1, t);
    service.lock(1, id, new CouponService.OrderInput("old-delay-order", 1, 10000));
    service.transition(1, id, "old-delay-order", "UNLOCK");
    service.lock(1, id, new CouponService.OrderInput("current-delay-order", 1, 10000));
    db.update("UPDATE user_coupon SET lock_until=0 WHERE user_id=1 AND id=?", id);
    service.consume(
        "old-lock-expiry",
        "LOCK_EXPIRE",
        Map.of("couponId", id, "userId", 1L, "orderId", "old-delay-order", "deliveryAt", 0L));
    assertThat(service.wallet(1).get(0).get("status")).isEqualTo("LOCKED");
    service.consume(
        "current-lock-expiry",
        "LOCK_EXPIRE",
        Map.of("couponId", id, "userId", 1L, "orderId", "current-delay-order", "deliveryAt", 0L));
    assertThat(service.wallet(1).get(0).get("status")).isEqualTo("AVAILABLE");
    db.update("UPDATE coupon_template SET valid_to=1 WHERE id=?", t);
    service.consume(
        "coupon-expiry", "COUPON_EXPIRE", Map.of("couponId", id, "userId", 1L, "deliveryAt", 0L));
    service.consume(
        "template-expiry", "TEMPLATE_EXPIRE", Map.of("templateId", t, "deliveryAt", 0L));
    assertThat(service.wallet(1).get(0).get("status")).isEqualTo("EXPIRED");
    assertThat(db.queryForObject("SELECT status FROM coupon_template WHERE id=?", String.class, t))
        .isEqualTo("ENDED");
  }

  @Test
  void orderIdentityCannotBeReusedByAnotherUser() {
    long t = template(2);
    String first = claim(1, t), second = claim(2, t);
    service.lock(1, first, new CouponService.OrderInput("global-order", 1, 10000));
    assertThatThrownBy(
            () -> service.lock(2, second, new CouponService.OrderInput("global-order", 1, 10000)))
        .isInstanceOf(BusinessException.class);
    assertThat(service.wallet(2).get(0).get("status")).isEqualTo("AVAILABLE");
  }
}
