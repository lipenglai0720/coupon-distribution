package cn.quanshu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;

class DiscountCalculatorTest {
  @Test
  void parallelChunksFilterAndMergeWithStableAmountOrdering() {
    var calculator = new DiscountCalculator();
    try {
      var wallet = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < 100; i++)
        wallet.add(
            Map.of(
                "id",
                String.format("%03d", i),
                "name",
                "test",
                "effective_status",
                i % 3 == 0 ? "USED" : "AVAILABLE",
                "merchant_id",
                i % 2 == 0 ? 1L : 2L,
                "valid_from",
                1L,
                "valid_to",
                1000L,
                "threshold_cents",
                100L,
                "discount_cents",
                (long) i));
      var expected =
          wallet.stream()
              .filter(
                  c ->
                      "AVAILABLE".equals(c.get("effective_status"))
                          && c.get("merchant_id").equals(1L))
              .map(c -> c.get("id"))
              .sorted(Comparator.comparing(Object::toString).reversed())
              .toList();
      assertThat(calculator.calculate(wallet, 1, 100, 500).stream().map(c -> c.get("id")).toList())
          .isEqualTo(expected);
      assertThat(calculator.calculate(wallet, 1, 99, 500)).isEmpty();
      assertThat(calculator.calculate(wallet, 1, 100, 1000)).isEmpty();
    } finally {
      calculator.stop();
    }
  }
}
