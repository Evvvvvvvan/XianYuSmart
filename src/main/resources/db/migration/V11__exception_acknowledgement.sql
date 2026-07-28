ALTER TABLE xianyu_goods_order
    ADD COLUMN exception_revision INT NOT NULL DEFAULT 0 AFTER attempt_count;

ALTER TABLE xianyu_goods_auto_reply_record
    ADD COLUMN exception_revision INT NOT NULL DEFAULT 0 AFTER attempt_count;

ALTER TABLE xianyu_kami_external_request
    ADD COLUMN exception_revision INT NOT NULL DEFAULT 0 AFTER attempt_count;

CREATE TABLE xianyu_exception_acknowledgement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    exception_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    source_version INT NOT NULL,
    handled_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_exception_acknowledgement (tenant_id, exception_type, source_id, source_version),
    KEY idx_exception_acknowledgement_tenant_time (tenant_id, handled_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
