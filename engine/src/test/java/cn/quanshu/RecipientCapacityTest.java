package cn.quanshu;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecipientCapacityTest {
  @TempDir Path directory;

  @Test
  void streamsOneMillionCsvRecipientsAndHonorsCheckpointAndLimit() throws Exception {
    Path csv = directory.resolve("recipients.csv");
    try (var out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      out.write("user_id\n");
      for (int i = 1; i <= 1_000_000; i++) {
        out.write(Integer.toString(i));
        out.newLine();
      }
    }

    var count = new AtomicLong();
    RecipientFileReader.read(
        csv,
        0,
        row -> {
          if (row.user() == null) return;
          long next = count.incrementAndGet();
          if (row.user() != next || row.number() != next + 1) {
            throw new AssertionError("Unexpected row after " + next);
          }
        });
    assertThat(count.get()).isEqualTo(1_000_000);
    var tail = new java.util.ArrayList<RecipientFileReader.Row>();
    RecipientFileReader.read(csv, 1_000_000, tail::add);
    assertThat(tail).containsExactly(new RecipientFileReader.Row(1_000_001, 1_000_000L, null));

    Files.writeString(csv, "1000001\n", StandardOpenOption.APPEND);
    assertThatThrownBy(() -> RecipientFileReader.read(csv, 1_000_001, row -> fail("over limit")))
        .isInstanceOf(BusinessException.class);
  }
}
