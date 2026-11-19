CREATE TABLE coupon_template (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, total_stock INT NOT NULL, remaining_stock INT NOT NULL,
 threshold_cents BIGINT NOT NULL, discount_cents BIGINT NOT NULL,
 valid_from BIGINT NOT NULL, valid_to BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 CHECK (remaining_stock >= 0), CHECK (discount_cents > 0)
) ENGINE=InnoDB;
CREATE TABLE user_coupon (
 id VARCHAR(36) PRIMARY KEY, template_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 request_id VARCHAR(100) NOT NULL, status VARCHAR(16) NOT NULL,
 order_id VARCHAR(100) NULL, lock_until BIGINT NULL, created_at BIGINT NOT NULL,
 UNIQUE KEY uk_user_template(user_id, template_id), UNIQUE KEY uk_request(request_id),
 UNIQUE KEY uk_order(order_id), INDEX idx_expiry(status, lock_until),
 FOREIGN KEY(template_id) REFERENCES coupon_template(id)
) ENGINE=InnoDB;
CREATE TABLE coupon_operation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, coupon_id VARCHAR(36) NOT NULL,
 order_id VARCHAR(100) NOT NULL, operation VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE KEY uk_order_operation(order_id, operation)
) ENGINE=InnoDB;
CREATE TABLE outbox (
 id VARCHAR(36) PRIMARY KEY, kind VARCHAR(32) NOT NULL, payload JSON NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt BIGINT NOT NULL, last_error VARCHAR(500), created_at BIGINT NOT NULL,
 INDEX idx_outbox(state,next_attempt)
) ENGINE=InnoDB;
CREATE TABLE inbox (event_id VARCHAR(36) PRIMARY KEY, processed_at BIGINT NOT NULL) ENGINE=InnoDB;
CREATE TABLE distribution_task (
 id VARCHAR(36) PRIMARY KEY, template_id BIGINT NOT NULL, created_at BIGINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE distribution_recipient (
 task_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 error VARCHAR(300), PRIMARY KEY(task_id,user_id)
) ENGINE=InnoDB;
