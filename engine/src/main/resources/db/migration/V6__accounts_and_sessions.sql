CREATE TABLE app_account (
  username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  account_role VARCHAR(16) NOT NULL,
  subject_id BIGINT NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until BIGINT NOT NULL DEFAULT 0,
  CHECK (account_role IN ('MERCHANT', 'CUSTOMER')),
  CHECK (subject_id > 0)
);

CREATE TABLE app_session (
  token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at BIGINT NOT NULL,
  INDEX idx_session_expiry(expires_at),
  INDEX idx_session_account(username),
  FOREIGN KEY (username) REFERENCES app_account(username)
);
