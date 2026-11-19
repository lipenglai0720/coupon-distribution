package cn.quanshu.sharding;

import static org.assertj.core.api.Assertions.*;

import cn.quanshu.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("sharded")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(
    named = "spring.profiles.active",
    matches = ".*sharded.*")
@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:13306/quanshu_test?serverTimezone=UTC}",
      "quanshu.mq-enabled=false",
      "quanshu.redis-enabled=false",
      "quanshu.jobs-enabled=false"
    })
class ShardingRoutingTest {
  @Autowired CouponService service;
  @Autowired DataSourceProperties properties;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate transaction;

  @Test
  void actualRowsRouteToFourPhysicalTablesAndGlobalRequestScopeIsEnforced() throws Exception {
    long now = System.currentTimeMillis();
    long template =
        service.create(
            new CouponService.TemplateInput(
                1, "physical shard verification", 10, 100, 20, now - 1000, now + 3600000));
    String request = UUID.randomUUID().toString();
    for (long user = 1; user <= 4; user++)
      service.claim(
          user,
          new CouponService.ClaimInput(
              template, user == 1 ? request : UUID.randomUUID().toString()));
    assertThatThrownBy(() -> service.claim(2, new CouponService.ClaimInput(template, request)))
        .isInstanceOf(BusinessException.class);
    assertThat(properties.getUrl()).contains("/quanshu_test?");
    try (var c =
            DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
        var query =
            c.prepareStatement(
                "SELECT COUNT(*) FROM quanshu_test_s0.user_coupon_0 WHERE template_id=? AND"
                    + " user_id=4")) {
      query.setLong(1, template);
      try (var r = query.executeQuery()) {
        r.next();
        assertThat(r.getInt(1)).isEqualTo(1);
      }
      for (int shard = 0; shard < 4; shard++)
        try (var s =
            c.prepareStatement(
                "SELECT COUNT(*) FROM "
                    + PhysicalShardLayout.database("quanshu_test", shard)
                    + ".user_coupon_"
                    + shard
                    + " WHERE template_id=?")) {
          s.setLong(1, template);
          try (var r = s.executeQuery()) {
            r.next();
            assertThat(r.getInt(1)).isEqualTo(1);
          }
        }
    }
  }

  @Test
  void merchantNumbersRouteTemplatesAndAllocateGloballyUniqueIds() throws Exception {
    long now = System.currentTimeMillis();
    var ids = new java.util.HashSet<Long>();
    for (long merchant = 1; merchant <= 4; merchant++) {
      long id =
          service.create(
              new CouponService.TemplateInput(
                  merchant, "merchant shard", 10, 100, 20, now - 1000, now + 3600000));
      ids.add(id);
      assertThat(properties.getUrl()).contains("/quanshu_test?");
      try (var c =
              DriverManager.getConnection(
                  properties.getUrl(), properties.getUsername(), properties.getPassword());
          var query =
              c.prepareStatement(
                  "SELECT shop_number FROM "
                      + PhysicalShardLayout.database(
                          "quanshu_test", UserHashAlgorithm.shard(merchant))
                      + ".coupon_template_"
                      + UserHashAlgorithm.shard(merchant)
                      + " WHERE id=?")) {
        query.setLong(1, id);
        try (var r = query.executeQuery()) {
          assertThat(r.next()).isTrue();
          assertThat(r.getLong(1)).isEqualTo(merchant);
        }
      }
      var coupon =
          service.claim(
              100 + merchant, new CouponService.ClaimInput(id, UUID.randomUUID().toString()));
      assertThat(coupon.get("template_id")).isEqualTo(id);
      assertThat(service.usable(100 + merchant, merchant, 100)).hasSize(1);
    }
    assertThat(ids).hasSize(4);
  }

  @Test
  void stockAndCouponRollbackAcrossPhysicalDatabases() {
    long now = System.currentTimeMillis();
    long template =
        service.create(
            new CouponService.TemplateInput(
                1, "Cross-database rollback", 10, 100, 20, now - 1000, now + 3600000));
    String request = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      service.claim(2, new CouponService.ClaimInput(template, request));
                      throw new IllegalStateException("rollback test");
                    }))
        .hasMessage("rollback test");
    assertThat(
            db.queryForObject(
                "SELECT remaining_stock FROM coupon_template WHERE id=?", Integer.class, template))
        .isEqualTo(10);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM user_coupon WHERE user_id=? AND template_id=?",
                Long.class,
                2L,
                template))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM request_identity WHERE request_id=?", Long.class, request))
        .isZero();
  }
}
