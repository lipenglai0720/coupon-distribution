package cn.quanshu;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Prevent accidentally reading the old single table after a sharding cutover. */
@Component
@Profile("!sharded")
@DependsOn("flywayInitializer")
class StorageModeGuard {
  StorageModeGuard(JdbcTemplate db) {
    if (!"SINGLE"
        .equals(db.queryForObject("SELECT mode FROM storage_mode WHERE id=1", String.class)))
      throw new IllegalStateException(
          "This database uses shards; activate the sharded Spring profile. Do not switch back to"
              + " the legacy table.");
  }
}
