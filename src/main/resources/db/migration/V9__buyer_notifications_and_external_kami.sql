CREATE TABLE xianyu_buyer_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    buyer_user_id VARCHAR(100) NOT NULL,
    buyer_user_name VARCHAR(200) NULL,
    tags_json TEXT NULL,
    note VARCHAR(500) NULL,
    automation_blocked TINYINT NOT NULL DEFAULT 0,
    blocked_reason VARCHAR(200) NULL,
    last_interaction_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_buyer_profile (tenant_id, xianyu_account_id, buyer_user_id),
    KEY idx_buyer_profile_status (tenant_id, automation_blocked, last_interaction_time),
    CONSTRAINT fk_buyer_profile_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_chat_message
    ADD KEY idx_chat_buyer_profile (tenant_id, xianyu_account_id, sender_user_id, message_time);

ALTER TABLE xianyu_goods_order
    ADD KEY idx_order_buyer_profile (tenant_id, xianyu_account_id, buyer_user_id, create_time);

INSERT INTO xianyu_buyer_profile (
    tenant_id, xianyu_account_id, buyer_user_id, buyer_user_name, last_interaction_time
)
SELECT
    message.tenant_id,
    message.xianyu_account_id,
    message.sender_user_id,
    MAX(message.sender_user_name),
    FROM_UNIXTIME(MAX(message.message_time) / 1000)
FROM xianyu_chat_message message
JOIN xianyu_account account ON account.id = message.xianyu_account_id
WHERE message.sender_user_id IS NOT NULL
  AND TRIM(message.sender_user_id) <> ''
  AND (account.unb IS NULL OR message.sender_user_id <> account.unb)
GROUP BY message.tenant_id, message.xianyu_account_id, message.sender_user_id;

INSERT INTO xianyu_buyer_profile (
    tenant_id, xianyu_account_id, buyer_user_id, buyer_user_name, last_interaction_time
)
SELECT
    orders.tenant_id,
    orders.xianyu_account_id,
    orders.buyer_user_id,
    MAX(orders.buyer_user_name),
    MAX(orders.create_time)
FROM xianyu_goods_order orders
WHERE orders.buyer_user_id IS NOT NULL
  AND TRIM(orders.buyer_user_id) <> ''
GROUP BY orders.tenant_id, orders.xianyu_account_id, orders.buyer_user_id
ON DUPLICATE KEY UPDATE
    buyer_user_name = COALESCE(VALUES(buyer_user_name), buyer_user_name),
    last_interaction_time = GREATEST(
        COALESCE(VALUES(last_interaction_time), '1970-01-01 00:00:00'),
        COALESCE(last_interaction_time, '1970-01-01 00:00:00')
    );

CREATE TABLE xianyu_notification_channel (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    channel_name VARCHAR(100) NOT NULL,
    webhook_url VARCHAR(1000) NOT NULL,
    signing_secret VARCHAR(200) NULL,
    event_types VARCHAR(500) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    last_success_time DATETIME(3) NULL,
    last_error_message VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_notification_channel_status (tenant_id, enabled, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE xianyu_notification_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    channel_id BIGINT NULL,
    event_type VARCHAR(50) NOT NULL,
    xianyu_account_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    send_status TINYINT NOT NULL,
    http_status INT NULL,
    error_message VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_notification_log_recent (tenant_id, create_time),
    KEY idx_notification_log_event (tenant_id, event_type, send_status),
    CONSTRAINT fk_notification_log_channel FOREIGN KEY (channel_id)
        REFERENCES xianyu_notification_channel (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_kami_config
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL' AFTER alias_name,
    ADD COLUMN external_api_url VARCHAR(1000) NULL AFTER source_type,
    ADD COLUMN external_api_headers TEXT NULL AFTER external_api_url,
    ADD COLUMN external_api_body TEXT NULL AFTER external_api_headers,
    ADD COLUMN external_api_result_path VARCHAR(200) NULL AFTER external_api_body,
    ADD COLUMN external_api_timeout_seconds INT NOT NULL DEFAULT 10 AFTER external_api_result_path;

CREATE TABLE xianyu_kami_external_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kami_config_id BIGINT NULL,
    xianyu_account_id BIGINT NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    request_token VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    request_status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    response_excerpt VARCHAR(500) NULL,
    error_message VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_external_kami_order (tenant_id, kami_config_id, order_id),
    KEY idx_external_kami_status (tenant_id, request_status, update_time),
    CONSTRAINT fk_external_kami_config FOREIGN KEY (kami_config_id)
        REFERENCES xianyu_kami_config (id) ON DELETE SET NULL,
    CONSTRAINT fk_external_kami_account FOREIGN KEY (xianyu_account_id)
        REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
