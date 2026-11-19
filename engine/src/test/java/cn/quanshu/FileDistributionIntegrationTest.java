package cn.quanshu;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:13306/quanshu_test?serverTimezone=UTC}",
      "quanshu.jobs-enabled=false",
      "quanshu.redis-enabled=false",
      "quanshu.mq-enabled=false",
      "quanshu.storage-dir=.local/test-uploads"
    })
class FileDistributionIntegrationTest {
  @Autowired FileDistribution files;
  @Autowired CouponService coupons;
  @Autowired JdbcTemplate db;

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
  }

  long template() {
    long time = System.currentTimeMillis();
    return coupons.create(
        new CouponService.TemplateInput(
            1, "导入测试", 10000, 10000, 2000, time - 1000, time + 3600000));
  }

  @Test
  void resumeFromCommittedCheckpointAndFencePreviousWorker() {
    StringBuilder csv = new StringBuilder("user_id\n");
    for (int i = 1; i <= 700; i++) csv.append(i).append('\n');
    try {
      String taskId =
          files.upload(
              template(),
              new MockMultipartFile(
                  "file",
                  "users.csv",
                  "text/csv",
                  csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var first = files.acquire();
      assertThat(first).isNotNull();
      List<RecipientFileReader.Row> committed = new ArrayList<>();
      committed.add(new RecipientFileReader.Row(1, null, null));
      for (int i = 1; i <= 499; i++)
        committed.add(new RecipientFileReader.Row(i + 1L, (long) i, null));
      files.flush(first, committed);
      assertThat(coupons.task(taskId).get("checkpoint")).isEqualTo(500L);
      db.update("UPDATE distribution_task SET lease_until=0 WHERE id=?", taskId);
      var next = files.acquire();
      assertThat(next.get("lease_owner")).isNotEqualTo(first.get("lease_owner"));
      assertThatThrownBy(() -> files.flush(first, committed)).hasMessageContaining("租约");
      files.process(next);
      assertThat(coupons.task(taskId).get("parse_state")).isEqualTo("COMPLETE");
      assertThat(db.queryForObject("SELECT COUNT(*) FROM distribution_recipient", Long.class))
          .isEqualTo(700);
      assertThat(
              db.queryForObject("SELECT COUNT(*) FROM outbox WHERE kind='BATCH_CLAIM'", Long.class))
          .isEqualTo(700);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void xlsxStreamingPreservesTextIdsAndRecordsBadRows() throws Exception {
    byte[] content;
    try (var workbook = new XSSFWorkbook();
        var bytes = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("名单");
      String[] values = {"user_id", "9007199254740993", "2", "2", "bad", "0"};
      for (int i = 0; i < values.length; i++)
        sheet.createRow(i).createCell(0).setCellValue(values[i]);
      workbook.write(bytes);
      content = bytes.toByteArray();
    }
    String id =
        files.upload(
            template(),
            new MockMultipartFile(
                "file",
                "users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content));
    files.process(files.acquire());
    var task = coupons.task(id);
    assertThat(task.get("parse_state")).isEqualTo("COMPLETE");
    assertThat(task.get("importErrors")).isEqualTo(2L);
    assertThat(
            db.queryForList(
                "SELECT user_id FROM distribution_recipient ORDER BY user_id", Long.class))
        .containsExactly(2L, 9007199254740993L);
    assertThat(
            db.queryForObject("SELECT COUNT(*) FROM outbox WHERE kind='BATCH_CLAIM'", Long.class))
        .isEqualTo(2);
  }

  @Test
  void unsupportedFileDoesNotCreateTask() {
    long template = template();
    assertThatThrownBy(
            () ->
                files.upload(
                    template,
                    new MockMultipartFile(
                        "file", "users.exe", "application/octet-stream", new byte[] {1})))
        .isInstanceOf(BusinessException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM distribution_task", Long.class)).isZero();
  }
}
