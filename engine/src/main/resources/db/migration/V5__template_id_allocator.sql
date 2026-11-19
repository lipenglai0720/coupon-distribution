CREATE TABLE id_allocator(name VARCHAR(32) PRIMARY KEY,next_id BIGINT NOT NULL) ENGINE=InnoDB;
INSERT INTO id_allocator SELECT 'template',COALESCE(MAX(id),0)+1 FROM coupon_template;
