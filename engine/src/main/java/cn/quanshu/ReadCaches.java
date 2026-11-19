package cn.quanshu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Configuration
@ConditionalOnProperty(name = "quanshu.redis-enabled", havingValue = "true")
class RedissonConfiguration {
  @Bean(destroyMethod = "shutdown")
  RedissonClient redisson(
      @Value("${spring.data.redis.host:127.0.0.1}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.database:0}") int database,
      @Value("${spring.data.redis.password:}") String password) {
    Config config = new Config();
    config.setThreads(2).setNettyThreads(2);
    var server =
        config
            .useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setDatabase(database)
            .setConnectionMinimumIdleSize(1)
            .setConnectionPoolSize(8)
            .setSubscriptionConnectionMinimumIdleSize(1)
            .setTimeout(2000);
    if (!password.isBlank()) server.setPassword(password);
    return Redisson.create(config);
  }
}

@Service
public class ReadCaches {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String EPOCH = "quanshu:catalog:epoch",
      HIGH_WATER = "quanshu:catalog:high-water";
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final RedissonClient locks;
  private final AtomicLong reads = new AtomicLong(),
      hits = new AtomicLong(),
      bloomRejects = new AtomicLong();

  public ReadCaches(
      JdbcTemplate db,
      StringRedisTemplate redis,
      ObjectMapper json,
      ObjectProvider<RedissonClient> locks) {
    this.db = db;
    this.redis = redis;
    this.json = json;
    this.locks = locks.getIfAvailable();
  }

  private RBloomFilter<Long> bloom() {
    return locks.getBloomFilter("quanshu:catalog:bloom");
  }

  private RLock catalog() {
    return locks.getLock("quanshu:catalog:write-lock");
  }

  private boolean acquire(RLock lock) {
    try {
      return lock.tryLock(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待缓存锁被中断", e);
    }
  }

  private static long value(String value) {
    return value == null ? 0 : Long.parseLong(value);
  }

  private void catchUpCatalog() {
    bloom().tryInit(1000000, 0.01);
    long high = value(redis.opsForValue().get(HIGH_WATER));
    for (long id :
        db.queryForList(
            "SELECT id FROM coupon_template WHERE id>? ORDER BY id", Long.class, high)) {
      bloom().add(id);
      high = id;
    }
    redis.opsForValue().set(HIGH_WATER, Long.toString(high));
  }

  @PostConstruct
  void warmCatalog() {
    if (locks == null) return;
    RLock lock = catalog();
    if (!acquire(lock)) throw new IllegalStateException("初始化模板目录超时");
    try {
      bloom().tryInit(1000000, 0.01);
      long high = 0;
      for (long id : db.queryForList("SELECT id FROM coupon_template ORDER BY id", Long.class)) {
        bloom().add(id);
        high = id;
      }
      redis.opsForValue().set(HIGH_WATER, Long.toString(high));
    } finally {
      lock.unlock();
    }
  }

  public void beforeTemplateCreate() {
    if (locks == null) return;
    RLock lock = catalog();
    if (!acquire(lock)) throw new BusinessException("模板目录繁忙，请稍后重试");
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (lock.isHeldByCurrentThread()) lock.unlock();
          }
        });
  }

  public void templateChanged(long template) {
    if (locks == null) return;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              bloom().tryInit(1000000, 0.01);
              bloom().add(template);
              catchUpCatalog();
              redis.opsForValue().increment(EPOCH);
            } catch (RuntimeException e) {
              /* TTL and next catalog catch-up recover cache; database remains authoritative. */
            }
          }
        });
  }

  public Map<String, Object> template(long id) {
    if (locks == null) return templateFromDb(id);
    try {
      String epoch = Objects.requireNonNullElse(redis.opsForValue().get(EPOCH), "0");
      String high = redis.opsForValue().get(HIGH_WATER);
      if (high != null && !bloom().contains(id) && (id <= value(high) || !catalog().isLocked())) {
        bloomRejects.incrementAndGet();
        return null;
      }
      String key = "quanshu:template:" + id + ":" + epoch;
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        hits.incrementAndGet();
        return cached.isEmpty() ? null : json.readValue(cached, MAP);
      }
      RLock lock = locks.getLock(key + ":fill");
      if (!acquire(lock)) return templateFromDb(id);
      try {
        cached = redis.opsForValue().get(key);
        if (cached != null) {
          hits.incrementAndGet();
          return cached.isEmpty() ? null : json.readValue(cached, MAP);
        }
        var result = templateFromDb(id);
        redis
            .opsForValue()
            .set(
                key,
                result == null ? "" : json.writeValueAsString(result),
                Duration.ofSeconds(
                    result == null ? 5 : 60 + ThreadLocalRandom.current().nextInt(30)));
        return result;
      } finally {
        lock.unlock();
      }
    } catch (Exception e) {
      return templateFromDb(id);
    }
  }

  private Map<String, Object> templateFromDb(long id) {
    reads.incrementAndGet();
    var rows = db.queryForList("SELECT * FROM coupon_template WHERE id=?", id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Long> stats() {
    return Map.of(
        "templateDatabaseReads",
        reads.get(),
        "templateCacheHits",
        hits.get(),
        "bloomRejections",
        bloomRejects.get());
  }

  public void walletChanged(long user) {
    if (locks == null) return;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              String key = "quanshu:wallet:" + user + ":epoch";
              redis.opsForValue().increment(key);
              redis.expire(key, Duration.ofDays(1));
            } catch (RuntimeException ignored) {
            }
          }
        });
  }

  public void lockWallet(long user) {
    if (locks == null) return;
    RLock lock = locks.getLock("quanshu:wallet:" + user + ":mutation");
    if (!acquire(lock)) throw new BusinessException("用户券操作繁忙，请稍后重试");
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (lock.isHeldByCurrentThread()) lock.unlock();
          }
        });
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private List<Map<String, Object>> walletFromDb(long user) {
    return db.queryForList(
        "SELECT c.*,t.name,t.merchant_id,t.threshold_cents,t.discount_cents,t.valid_from,t.valid_to"
            + " FROM user_coupon c JOIN coupon_template t ON c.template_id=t.id WHERE c.user_id=?"
            + " ORDER BY c.created_at DESC",
        user);
  }

  private List<Map<String, Object>> readWalletSnapshot(String base) throws java.io.IOException {
    String ready = redis.opsForValue().get(base + "ready");
    if (ready == null || !ready.startsWith("v2:")) return null;
    int expected = Integer.parseInt(ready.substring(3));
    if (expected == 0) return new ArrayList<>();
    var ids = redis.opsForZSet().reverseRange(base + "index", 0, -1);
    if (ids == null || ids.size() != expected) return null;
    var cached =
        redis.executePipelined(
            (RedisCallback<Object>)
                connection -> {
                  for (String id : ids) connection.stringCommands().get(bytes(base + id));
                  return null;
                });
    if (cached.size() != expected || cached.stream().anyMatch(Objects::isNull)) return null;
    var snapshot = new ArrayList<Map<String, Object>>(expected);
    for (Object entry : cached) snapshot.add(json.readValue(entry.toString(), MAP));
    return snapshot;
  }

  private void writeWalletSnapshot(String base, List<Map<String, Object>> snapshot) {
    redis.executePipelined(
        (RedisCallback<Object>)
            connection -> {
              connection.keyCommands().del(bytes(base + "ready"), bytes(base + "index"));
              for (var row : snapshot) {
                String id = row.get("id").toString();
                try {
                  connection
                      .stringCommands()
                      .setEx(bytes(base + id), 60, bytes(json.writeValueAsString(row)));
                } catch (java.io.IOException e) {
                  throw new IllegalStateException(e);
                }
                connection
                    .zSetCommands()
                    .zAdd(
                        bytes(base + "index"),
                        ((Number) row.get("created_at")).doubleValue(),
                        bytes(id));
              }
              // The index survives past the readiness marker. The count detects an incomplete
              // index.
              connection.keyCommands().expire(bytes(base + "index"), 60);
              connection
                  .stringCommands()
                  .setEx(bytes(base + "ready"), 30, bytes("v2:" + snapshot.size()));
              return null;
            });
  }

  public List<Map<String, Object>> wallet(long user) {
    List<Map<String, Object>> result = null;
    if (locks != null) {
      try {
        String epoch =
            Objects.requireNonNullElse(
                redis.opsForValue().get("quanshu:wallet:" + user + ":epoch"), "0");
        String base = "quanshu:wallet:{" + user + "}:" + epoch + ":";
        result = readWalletSnapshot(base);
        if (result == null) {
          RLock fill = locks.getLock(base + "fill");
          if (acquire(fill)) {
            try {
              result = readWalletSnapshot(base);
              if (result == null) {
                result = walletFromDb(user);
                writeWalletSnapshot(base, result);
              }
            } finally {
              fill.unlock();
            }
          }
        }
      } catch (Exception e) {
        result = null;
      }
    }
    if (result == null) result = walletFromDb(user);
    long now = System.currentTimeMillis();
    for (var row : result)
      row.put(
          "effective_status",
          "AVAILABLE".equals(row.get("status")) && ((Number) row.get("valid_to")).longValue() <= now
              ? "EXPIRED"
              : row.get("status"));
    return result;
  }
}
