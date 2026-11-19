package cn.quanshu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Recover accepted messages whose business processing was never acknowledged in MySQL. */
@Service
public class MessageRecovery {
  private final JdbcTemplate db;

  MessageRecovery(JdbcTemplate db) {
    this.db = db;
  }

  public void repair() {
    db.update(
        "UPDATE outbox SET"
            + " state=IF(attempts>=10,'FAILED','PENDING'),attempts=attempts+1,next_attempt=?,last_error='Consumption"
            + " acknowledgement timeout' WHERE state='SENT' AND next_attempt<=? LIMIT 200",
        System.currentTimeMillis(),
        System.currentTimeMillis());
  }
}
