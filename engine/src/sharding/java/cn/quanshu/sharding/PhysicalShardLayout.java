package cn.quanshu.sharding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves hash tables into two databases on one MySQL server without splitting the JDBC transaction.
 */
final class PhysicalShardLayout {
  static final String MODE = "TWO_SCHEMA_HASH_4";

  private PhysicalShardLayout() {}

  static void prepare(Connection connection, String oldMode) throws SQLException {
    String base = connection.getCatalog();
    if (base == null || !base.matches("[a-zA-Z0-9_]{1,61}"))
      throw new IllegalArgumentException(
          "Use a MySQL database name with at most 61 letters, digits or underscores");
    if (MODE.equals(oldMode)) {
      requireViews(connection, base);
      return;
    }
    try (var statement = connection.createStatement()) {
      for (int i = 0; i < 2; i++) {
        statement.execute(
            "CREATE DATABASE IF NOT EXISTS " + quoted(base + "_s" + i) + " CHARACTER SET utf8mb4");
      }
      List<String> moves = new ArrayList<>();
      for (int shard = 0; shard < 4; shard++) {
        for (String prefix : List.of("coupon_template_", "user_coupon_")) {
          String table = prefix + shard;
          String target = database(base, shard);
          String sourceType = type(connection, base, table);
          String destinationType = type(connection, target, table);
          if ("BASE TABLE".equals(sourceType) && destinationType == null) {
            moves.add(qualified(base, table) + " TO " + qualified(target, table));
          } else if (!"BASE TABLE".equals(destinationType)
              || (sourceType != null && !"VIEW".equals(sourceType))) {
            throw new IllegalStateException("Unexpected source or destination for shard " + table);
          }
        }
      }
      if (!moves.isEmpty()) statement.execute("RENAME TABLE " + String.join(", ", moves));
      for (int shard = 0; shard < 4; shard++) {
        String target = database(base, shard);
        String templates = "coupon_template_" + shard;
        String coupons = "user_coupon_" + shard;
        if (columnExists(connection, target, templates, "merchant_id")) {
          statement.execute(
              "ALTER TABLE "
                  + qualified(target, templates)
                  + " RENAME COLUMN merchant_id TO shop_number");
        }
        createView(
            statement,
            base,
            templates,
            target,
            "id,shop_number AS merchant_id,name,total_stock,remaining_stock,threshold_cents,"
                + "discount_cents,valid_from,valid_to,status");
        createView(
            statement,
            base,
            coupons,
            target,
            "id,template_id,user_id,request_id,status,order_id,lock_until,created_at");
      }
      requireViews(connection, base);
      statement.executeUpdate("UPDATE storage_mode SET mode='" + MODE + "' WHERE id=1");
    }
  }

  static String database(String base, int shard) {
    return base + "_s" + (shard / 2);
  }

  private static void requireViews(Connection connection, String base) throws SQLException {
    for (int i = 0; i < 4; i++) {
      for (String table : List.of("coupon_template_" + i, "user_coupon_" + i)) {
        if (!"VIEW".equals(type(connection, base, table))
            || !"BASE TABLE".equals(type(connection, database(base, i), table))) {
          throw new IllegalStateException("Missing provisioned MySQL shard " + table);
        }
      }
    }
  }

  private static void createView(
      java.sql.Statement statement, String base, String table, String target, String columns)
      throws SQLException {
    statement.execute(
        "CREATE OR REPLACE ALGORITHM=MERGE SQL SECURITY INVOKER VIEW "
            + qualified(base, table)
            + " AS SELECT "
            + columns
            + " FROM "
            + qualified(target, table));
  }

  private static boolean columnExists(
      Connection connection, String schema, String table, String column) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema=? AND table_name=? AND column_name=?")) {
      query.setString(1, schema);
      query.setString(2, table);
      query.setString(3, column);
      try (var result = query.executeQuery()) {
        return result.next();
      }
    }
  }

  private static String type(Connection connection, String schema, String table)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT table_type FROM information_schema.tables"
                + " WHERE table_schema=? AND table_name=?")) {
      query.setString(1, schema);
      query.setString(2, table);
      try (var result = query.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static String qualified(String schema, String table) {
    return quoted(schema) + "." + quoted(table);
  }

  private static String quoted(String name) {
    return "`" + name + "`";
  }
}
