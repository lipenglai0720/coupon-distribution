CREATE TABLE claim_request (
 request_id VARCHAR(100) PRIMARY KEY,
 template_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 coupon_id VARCHAR(36), error VARCHAR(300),
 issued_new BOOLEAN NOT NULL DEFAULT FALSE,
 retries INT NOT NULL DEFAULT 0,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 active_user BIGINT GENERATED ALWAYS AS (IF(state='PENDING',user_id,NULL)) STORED,
 UNIQUE KEY uk_claim_user_template(active_user,template_id),
 INDEX idx_claim_repair(state,updated_at)
) ENGINE=InnoDB;
