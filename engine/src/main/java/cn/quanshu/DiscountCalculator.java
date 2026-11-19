package cn.quanshu;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Parallelises sufficiently large wallets; small wallets avoid task scheduling overhead. */
@Component
public class DiscountCalculator {
  private final ThreadPoolExecutor pool =
      new ThreadPoolExecutor(
          2,
          4,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(128),
          new ThreadPoolExecutor.CallerRunsPolicy());

  @PreDestroy
  void stop() {
    pool.shutdown();
  }

  public List<Map<String, Object>> calculate(
      List<Map<String, Object>> wallet, long merchant, long amount, long now) {
    var result = new ArrayList<Map<String, Object>>();
    if (wallet.size() < 32) result.addAll(part(wallet, merchant, amount, now));
    else {
      var futures = new ArrayList<CompletableFuture<List<Map<String, Object>>>>();
      for (int start = 0; start < wallet.size(); start += 32) {
        var slice = wallet.subList(start, Math.min(start + 32, wallet.size()));
        futures.add(CompletableFuture.supplyAsync(() -> part(slice, merchant, amount, now), pool));
      }
      for (var future : futures) result.addAll(future.join());
    }
    result.sort(
        Comparator.comparingLong((Map<String, Object> c) -> n(c, "discount_cents"))
            .reversed()
            .thenComparing(c -> c.get("id").toString()));
    return result;
  }

  private static long n(Map<String, Object> c, String key) {
    return ((Number) c.get(key)).longValue();
  }

  private List<Map<String, Object>> part(
      List<Map<String, Object>> wallet, long merchant, long amount, long now) {
    var result = new ArrayList<Map<String, Object>>();
    for (var c : wallet)
      if ("AVAILABLE".equals(c.get("effective_status"))
          && n(c, "merchant_id") == merchant
          && n(c, "valid_from") <= now
          && n(c, "valid_to") > now
          && n(c, "threshold_cents") <= amount)
        result.add(
            Map.of(
                "id",
                c.get("id"),
                "name",
                c.get("name"),
                "discount_cents",
                Math.min(amount, n(c, "discount_cents"))));
    return result;
  }
}
