CREATE TABLE request_identity (
 request_id VARCHAR(100) PRIMARY KEY, user_id BIGINT NOT NULL, template_id BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO request_identity SELECT request_id,user_id,template_id FROM user_coupon;
CREATE TABLE order_identity (
 order_id VARCHAR(100) PRIMARY KEY, user_id BIGINT NOT NULL, coupon_id VARCHAR(36) NOT NULL
) ENGINE=InnoDB;
INSERT INTO order_identity SELECT order_id,user_id,id FROM user_coupon WHERE order_id IS NOT NULL;
INSERT IGNORE INTO order_identity SELECT o.order_id,c.user_id,c.id FROM coupon_operation o JOIN user_coupon c ON c.id=o.coupon_id WHERE o.operation='LOCK';
CREATE TABLE storage_mode (id INT PRIMARY KEY, mode VARCHAR(32) NOT NULL) ENGINE=InnoDB;
INSERT INTO storage_mode VALUES(1,'SINGLE');
