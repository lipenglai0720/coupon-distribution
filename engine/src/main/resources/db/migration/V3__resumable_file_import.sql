ALTER TABLE distribution_task
 ADD COLUMN file_path VARCHAR(1000),
 ADD COLUMN parse_state VARCHAR(16) NOT NULL DEFAULT 'COMPLETE',
 ADD COLUMN checkpoint BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN total_rows BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN lease_owner VARCHAR(36),
 ADD COLUMN lease_until BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN parse_error VARCHAR(500),
 ADD INDEX idx_parse_lease(parse_state,lease_until);
CREATE TABLE distribution_import_error (
 task_id VARCHAR(36) NOT NULL, source_row BIGINT NOT NULL,
 error VARCHAR(300) NOT NULL,
 PRIMARY KEY(task_id,source_row)
) ENGINE=InnoDB;
