package cn.quanshu;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

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
class ReadCachesIntegrationTest {
  @Autowired CouponService coupons;
  @Autowired ReadCaches cache;
  @Autowired JdbcTemplate db;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void reset() throws Exception {
    try (var c = Objects.requireNonNull(db.getDataSource()).getConnection()) {
      assertThat(c.getMetaData().getURL()).contains("/quanshu_test?");
    }
    for (String t :
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
            "coupon_template")) db.update("DELETE FROM " + t);
    try (var c = Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
      c.serverCommands().flushDb();
    }
  }

  long template() {
    long time = System.currentTimeMillis();
    return coupons.create(
        new CouponService.TemplateInput(1, "缓存测试", 10, 10000, 2000, time - 1000, time + 3600000));
  }

  @Test
  void templateReadHitsCacheAndEndInvalidatesIt() {
    long id = template(), before = cache.stats().get("templateDatabaseReads");
    for (int i = 0; i < 10; i++) assertThat(cache.template(id).get("status")).isEqualTo("ACTIVE");
    assertThat(cache.stats().get("templateDatabaseReads") - before).isZero();
    coupons.end(id);
    assertThat(cache.template(id).get("status")).isEqualTo("ENDED");
  }

  @Test
  void bloomNegativeDoesNotHideLaterCreatedTemplate() {
    long id = template(), before = cache.stats().get("templateDatabaseReads");
    assertThat(cache.template(id + 1)).isNull();
    assertThat(cache.stats().get("templateDatabaseReads")).isEqualTo(before);
    long next = template();
    assertThat(next).isEqualTo(id + 1);
    assertThat(cache.template(next)).isNotNull();
  }

  @Test
  void zsetPipelineWalletTracksStateTransitions() {
    long id = template();
    String coupon =
        coupons.claim(1, new CouponService.ClaimInput(id, "cache-wallet")).get("id").toString();
    assertThat(coupons.wallet(1)).hasSize(1);
    assertThat(coupons.wallet(1).get(0).get("status")).isEqualTo("AVAILABLE");
    coupons.lock(1, coupon, new CouponService.OrderInput("cache-order", 1, 10000));
    assertThat(coupons.wallet(1).get(0).get("status")).isEqualTo("LOCKED");
    assertThat(coupons.usable(1, 1, 10000)).isEmpty();
    coupons.transition(1, coupon, "cache-order", "REDEEM");
    coupons.transition(1, coupon, "cache-order", "REFUND");
    assertThat(coupons.wallet(1).get(0).get("status")).isEqualTo("AVAILABLE");
    assertThat(coupons.usable(1, 1, 10000)).hasSize(1);
  }

  @Test
  void missingWalletIndexWithLiveReadyMarkerReloadsFromDatabase() {
    long id = template();
    coupons.claim(1, new CouponService.ClaimInput(id, "wallet-index-renewal"));
    assertThat(coupons.usable(1, 1, 10000)).hasSize(1);
    String epoch = redis.opsForValue().get("quanshu:wallet:1:epoch");
    String base = "quanshu:wallet:{1}:" + epoch + ":";
    assertThat(redis.hasKey(base + "ready")).isTrue();
    assertThat(redis.delete(base + "index")).isTrue();
    assertThat(coupons.usable(1, 1, 10000)).hasSize(1);
  }

  @Test
  void expirySweepInvalidatesCachedWallet() {
    long id = template();
    coupons.claim(1, new CouponService.ClaimInput(id, "cache-expiry"));
    coupons.wallet(1);
    db.update(
        "UPDATE coupon_template SET valid_to=? WHERE id=?", System.currentTimeMillis() - 1, id);
    coupons.expire();
    assertThat(coupons.wallet(1).get(0).get("effective_status")).isEqualTo("EXPIRED");
  }
}
