package cn.quanshu.sharding;

import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import javax.sql.DataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.*;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.single.config.SingleRuleConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@Profile("sharded")
@EnableConfigurationProperties(DataSourceProperties.class)
public class ShardingConfiguration {
  @Bean(destroyMethod = "close")
  DataSource dataSource(DataSourceProperties properties) throws Exception {
    HikariDataSource raw =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    raw.setMaximumPoolSize(10);
    raw.setMinimumIdle(1);
    try {
      Flyway.configure().dataSource(raw).locations("classpath:db/migration").load().migrate();
      try (var c = raw.getConnection();
          var s = c.createStatement()) {
        try (var r = s.executeQuery("SELECT GET_LOCK('quanshu-shard-bootstrap',60)")) {
          if (!r.next() || r.getInt(1) != 1)
            throw new IllegalStateException("Shard bootstrap lock timeout");
        }
        try {
          String mode;
          try (var r = s.executeQuery("SELECT mode FROM storage_mode WHERE id=1")) {
            r.next();
            mode = r.getString(1);
          }
          if ("SINGLE".equals(mode) || "USER_HASH_4".equals(mode)) {
            for (int i = 0; i < 4; i++) {
              if ("SINGLE".equals(mode))
                s.execute("CREATE TABLE IF NOT EXISTS user_coupon_" + i + " LIKE user_coupon");
              s.execute(
                  "CREATE TABLE IF NOT EXISTS coupon_template_" + i + " LIKE coupon_template");
            }
            c.setAutoCommit(false);
            try {
              for (int i = 0; i < 4; i++) {
                if ("SINGLE".equals(mode))
                  s.executeUpdate(
                      "INSERT INTO user_coupon_"
                          + i
                          + " SELECT * FROM user_coupon WHERE MOD((user_id ^ (user_id >> 32)),4)="
                          + i);
                s.executeUpdate(
                    "INSERT INTO coupon_template_"
                        + i
                        + " SELECT * FROM coupon_template WHERE MOD((merchant_id ^ (merchant_id >>"
                        + " 32)),4)="
                        + i);
              }
              s.executeUpdate("UPDATE storage_mode SET mode='USER_TEMPLATE_HASH_4' WHERE id=1");
              c.commit();
            } catch (Exception e) {
              c.rollback();
              throw e;
            } finally {
              c.setAutoCommit(true);
            }
          } else if (!"USER_TEMPLATE_HASH_4".equals(mode) && !PhysicalShardLayout.MODE.equals(mode))
            throw new IllegalStateException("Unknown storage mode " + mode);
          PhysicalShardLayout.prepare(c, mode);
        } finally {
          s.execute("SELECT RELEASE_LOCK('quanshu-shard-bootstrap')");
        }
      }
      var sharding = new ShardingRuleConfiguration();
      var table = new ShardingTableRuleConfiguration("user_coupon", "ds.user_coupon_${0..3}");
      table.setTableShardingStrategy(
          new StandardShardingStrategyConfiguration("user_id", "user_hash"));
      sharding.getTables().add(table);
      var templates =
          new ShardingTableRuleConfiguration("coupon_template", "ds.coupon_template_${0..3}");
      templates.setTableShardingStrategy(
          new StandardShardingStrategyConfiguration("merchant_id", "user_hash"));
      sharding.getTables().add(templates);
      var algorithm = new Properties();
      algorithm.setProperty("strategy", "STANDARD");
      algorithm.setProperty("algorithmClassName", UserHashAlgorithm.class.getName());
      sharding
          .getShardingAlgorithms()
          .put("user_hash", new AlgorithmConfiguration("CLASS_BASED", algorithm));
      var single = new SingleRuleConfiguration();
      single.setTables(List.of("ds.*"));
      single.setDefaultDataSource("ds");
      var props = new Properties();
      props.setProperty("sql-show", "false");
      props.setProperty("kernel-executor-size", "2");
      return ShardingSphereDataSourceFactory.createDataSource(
          Map.of("ds", raw), List.of(sharding, single), props);
    } catch (Exception e) {
      raw.close();
      throw e;
    }
  }
}
