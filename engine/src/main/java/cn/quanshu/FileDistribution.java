package cn.quanshu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileDistribution {
  private static class LeaseLost extends RuntimeException {
    LeaseLost() {
      super("任务租约已失效");
    }
  }

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final Path storage;

  public FileDistribution(
      JdbcTemplate db,
      ObjectMapper json,
      PlatformTransactionManager transactions,
      @Value("${quanshu.storage-dir:./data/uploads}") String storage) {
    this.db = db;
    this.json = json;
    this.tx = new TransactionTemplate(transactions);
    this.storage = Path.of(storage).toAbsolutePath().normalize();
  }

  public String upload(long template, MultipartFile file) throws java.io.IOException {
    if (file.isEmpty()) throw new BusinessException("文件为空");
    String name =
        Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
    String extension = name.endsWith(".csv") ? ".csv" : name.endsWith(".xlsx") ? ".xlsx" : null;
    if (extension == null) throw new BusinessException("支持 UTF-8 单列 CSV 或 XLSX 文件");
    if (db.queryForObject("SELECT COUNT(*) FROM coupon_template WHERE id=?", Long.class, template)
        != 1) throw new BusinessException("模板不存在");
    Files.createDirectories(storage);
    String id = UUID.randomUUID().toString();
    Path path = storage.resolve(id + extension);
    try (var stream = file.getInputStream()) {
      Files.copy(stream, path);
    }
    try {
      tx.executeWithoutResult(
          s ->
              db.update(
                  "INSERT INTO distribution_task(id,template_id,created_at,file_path,parse_state)"
                      + " VALUES(?,?,?,?,'PENDING')",
                  id,
                  template,
                  System.currentTimeMillis(),
                  path.toString()));
    } catch (RuntimeException e) {
      Files.deleteIfExists(path);
      throw e;
    }
    return id;
  }

  public Map<String, Object> acquire() {
    return tx.execute(
        status -> {
          var tasks =
              db.queryForList(
                  "SELECT * FROM distribution_task WHERE parse_state IN ('PENDING','PROCESSING')"
                      + " AND lease_until<? ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                  System.currentTimeMillis());
          if (tasks.isEmpty()) return null;
          var task = tasks.get(0);
          String owner = UUID.randomUUID().toString();
          db.update(
              "UPDATE distribution_task SET parse_state='PROCESSING',lease_owner=?,lease_until=?"
                  + " WHERE id=?",
              owner,
              System.currentTimeMillis() + 60000,
              task.get("id"));
          task.put("lease_owner", owner);
          return task;
        });
  }

  private void fence(Map<String, Object> task) {
    var rows =
        db.queryForList(
            "SELECT lease_owner,lease_until FROM distribution_task WHERE id=? FOR UPDATE",
            task.get("id"));
    if (rows.isEmpty()
        || !Objects.equals(rows.get(0).get("lease_owner"), task.get("lease_owner"))
        || ((Number) rows.get(0).get("lease_until")).longValue() < System.currentTimeMillis())
      throw new LeaseLost();
  }

  public void flush(Map<String, Object> task, List<RecipientFileReader.Row> rows) {
    if (rows.isEmpty()) return;
    tx.executeWithoutResult(
        status -> {
          fence(task);
          for (var row : rows) {
            if (row.error() != null)
              db.update(
                  "INSERT IGNORE INTO distribution_import_error(task_id,source_row,error)"
                      + " VALUES(?,?,?)",
                  task.get("id"),
                  row.number(),
                  row.error());
            if (row.user() != null) {
              int inserted =
                  db.update(
                      "INSERT IGNORE INTO distribution_recipient(task_id,user_id) VALUES(?,?)",
                      task.get("id"),
                      row.user());
              if (inserted == 1) {
                try {
                  long time = System.currentTimeMillis();
                  db.update(
                      "INSERT INTO outbox(id,kind,payload,next_attempt,created_at)"
                          + " VALUES(?,'BATCH_CLAIM',?,?,?)",
                      UUID.randomUUID().toString(),
                      json.writeValueAsString(
                          Map.of(
                              "taskId",
                              task.get("id"),
                              "templateId",
                              task.get("template_id"),
                              "userId",
                              row.user())),
                      time,
                      time);
                } catch (java.io.IOException e) {
                  throw new IllegalStateException(e);
                }
              }
            }
          }
          db.update(
              "UPDATE distribution_task SET checkpoint=?,lease_until=? WHERE id=?",
              rows.get(rows.size() - 1).number(),
              System.currentTimeMillis() + 60000,
              task.get("id"));
        });
  }

  public void process(Map<String, Object> task) {
    List<RecipientFileReader.Row> chunk = new ArrayList<>(500);
    try {
      Path path = Path.of(task.get("file_path").toString()).toAbsolutePath().normalize();
      if (!path.startsWith(storage)) throw new BusinessException("任务文件路径无效");
      RecipientFileReader.read(
          path,
          ((Number) task.get("checkpoint")).longValue(),
          row -> {
            chunk.add(row);
            if (chunk.size() == 500) {
              flush(task, chunk);
              chunk.clear();
            }
          });
      flush(task, chunk);
      tx.executeWithoutResult(
          s -> {
            fence(task);
            db.update(
                "UPDATE distribution_task SET"
                    + " parse_state='COMPLETE',total_rows=checkpoint,lease_owner=NULL,lease_until=0"
                    + " WHERE id=?",
                task.get("id"));
          });
    } catch (LeaseLost ignored) {
      // The next owner resumes from the last committed source row.
    } catch (Exception e) {
      // A competing lease owner may already be progressing this task; never overwrite its state.
      String error = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
      db.update(
          "UPDATE distribution_task SET"
              + " parse_state='FAILED',parse_error=?,lease_owner=NULL,lease_until=0 WHERE id=? AND"
              + " lease_owner=?",
          error.substring(0, Math.min(500, error.length())),
          task.get("id"),
          task.get("lease_owner"));
    }
  }

  public void retry(String id) {
    if (db.update(
            "UPDATE distribution_task SET parse_state='PENDING',parse_error=NULL,lease_until=0"
                + " WHERE id=? AND parse_state='FAILED'",
            id)
        != 1) throw new BusinessException("任务不存在或当前不可重试");
  }
}

@Service
@ConditionalOnProperty(name = "quanshu.jobs-enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "!${quanshu.xxl-enabled:false}")
class FileImportWorker {
  private final FileDistribution files;

  FileImportWorker(FileDistribution files) {
    this.files = files;
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    var task = files.acquire();
    if (task != null) files.process(task);
  }
}
