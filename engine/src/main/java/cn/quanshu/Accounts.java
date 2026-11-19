package cn.quanshu;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts {
  public enum Role {
    CUSTOMER,
    MERCHANT
  }

  public record Identity(String username, Role role, long subjectId) {}

  public record Session(Identity identity, String token, long expiresAt) {}

  private final JdbcTemplate db;
  private final SecureRandom random = new SecureRandom();
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  private final String unknownPasswordHash = passwords.encode(UUID.randomUUID().toString());
  private final Semaphore loginWorkers = new Semaphore(4);

  Accounts(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public Optional<Session> login(String username, String password) {
    if (!validUsername(username)
        || password.getBytes(StandardCharsets.UTF_8).length > 72
        || !loginWorkers.tryAcquire()) return Optional.empty();
    try {
      long now = System.currentTimeMillis();
      var rows = db.queryForList("SELECT * FROM app_account WHERE username=? FOR UPDATE", username);
      var account = rows.isEmpty() ? null : rows.get(0);
      boolean enabled = account != null && Boolean.TRUE.equals(account.get("enabled"));
      boolean locked = enabled && ((Number) account.get("locked_until")).longValue() > now;
      if (locked) return Optional.empty();
      boolean validPassword =
          passwords.matches(
              password, enabled ? account.get("password_hash").toString() : unknownPasswordHash);
      if (!enabled) return Optional.empty();
      if (!validPassword) {
        int attempts = ((Number) account.get("failed_attempts")).intValue() + 1;
        db.update(
            "UPDATE app_account SET failed_attempts=?,locked_until=? WHERE username=?",
            attempts >= 5 ? 0 : attempts,
            attempts >= 5 ? now + Duration.ofMinutes(15).toMillis() : 0,
            username);
        return Optional.empty();
      }
      var identity = identity(account);
      byte[] secret = new byte[32];
      random.nextBytes(secret);
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
      long expiresAt = now + Duration.ofHours(2).toMillis();
      db.update(
          "UPDATE app_account SET failed_attempts=0,locked_until=0 WHERE username=?", username);
      db.update("DELETE FROM app_session WHERE username=? AND expires_at<=?", username, now);
      db.update(
          "INSERT INTO app_session(token_digest,username,expires_at) VALUES(?,?,?)",
          digest(token),
          username,
          expiresAt);
      return Optional.of(new Session(identity, token, expiresAt));
    } finally {
      loginWorkers.release();
    }
  }

  Optional<Identity> authenticate(String token) {
    if (!validToken(token)) return Optional.empty();
    return db
        .queryForList(
            "SELECT a.username,a.account_role,a.subject_id FROM app_session s JOIN app_account a ON"
                + " a.username=s.username WHERE s.token_digest=? AND s.expires_at>? AND"
                + " a.enabled=TRUE",
            digest(token),
            System.currentTimeMillis())
        .stream()
        .findFirst()
        .map(Accounts::identity);
  }

  void logout(String token) {
    if (validToken(token)) db.update("DELETE FROM app_session WHERE token_digest=?", digest(token));
  }

  @Transactional
  public void provision(List<SeedAccount> accounts) {
    for (var account : accounts) {
      if (!validUsername(account.username())
          || account.role() == null
          || account.subjectId() < 1
          || account.password() == null
          || account.password().length() < 12
          || account.password().getBytes(StandardCharsets.UTF_8).length > 72)
        throw new IllegalArgumentException("Account file has an invalid account entry");
      db.update(
          "INSERT INTO app_account(username,account_role,subject_id,password_hash)"
              + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE username=app_account.username",
          account.username(),
          account.role().name(),
          account.subjectId(),
          passwords.encode(account.password()));
    }
  }

  public record SeedAccount(String username, Role role, long subjectId, String password) {}

  void removeExpiredSessions() {
    db.update("DELETE FROM app_session WHERE expires_at<=? LIMIT 1000", System.currentTimeMillis());
  }

  private static Identity identity(Map<String, Object> row) {
    return new Identity(
        row.get("username").toString(),
        Role.valueOf(row.get("account_role").toString()),
        ((Number) row.get("subject_id")).longValue());
  }

  private static boolean validUsername(String username) {
    return username != null && username.matches("[a-zA-Z0-9_-]{3,64}");
  }

  private static boolean validToken(String token) {
    return token != null && token.matches("[a-zA-Z0-9_-]{43}");
  }

  private static String digest(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
