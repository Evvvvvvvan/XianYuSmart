-- 多租户隔离、商品运营自动化和商家运营资源

ALTER TABLE xianyu_account ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_cookie ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_chat_message ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods_config ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods_auto_delivery_config ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods_order ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods_auto_reply_record ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_operation_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_sys_setting ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_kami_config ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_kami_item ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_kami_usage_record ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_keyword_reply_rule ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_keyword_reply_content ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods_sku ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_goods_sku_property ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;
ALTER TABLE xianyu_human_intervention_record ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 AFTER id;

SET @seed_tenant = COALESCE((SELECT MIN(id) FROM sys_user), 1);
UPDATE xianyu_account SET tenant_id = @seed_tenant;
UPDATE xianyu_cookie c JOIN xianyu_account a ON a.id = c.xianyu_account_id SET c.tenant_id = a.tenant_id;
UPDATE xianyu_goods g JOIN xianyu_account a ON a.id = g.xianyu_account_id SET g.tenant_id = a.tenant_id;
UPDATE xianyu_chat_message m JOIN xianyu_account a ON a.id = m.xianyu_account_id SET m.tenant_id = a.tenant_id;
UPDATE xianyu_goods_config c JOIN xianyu_account a ON a.id = c.xianyu_account_id SET c.tenant_id = a.tenant_id;
UPDATE xianyu_goods_auto_delivery_config c JOIN xianyu_account a ON a.id = c.xianyu_account_id SET c.tenant_id = a.tenant_id;
UPDATE xianyu_goods_order o JOIN xianyu_account a ON a.id = o.xianyu_account_id SET o.tenant_id = a.tenant_id;
UPDATE xianyu_goods_auto_reply_record r JOIN xianyu_account a ON a.id = r.xianyu_account_id SET r.tenant_id = a.tenant_id;
UPDATE xianyu_operation_log l LEFT JOIN xianyu_account a ON a.id = l.xianyu_account_id SET l.tenant_id = COALESCE(a.tenant_id, @seed_tenant);
UPDATE xianyu_sys_setting SET tenant_id = @seed_tenant;
UPDATE xianyu_kami_config c JOIN xianyu_account a ON a.id = c.xianyu_account_id SET c.tenant_id = a.tenant_id;
UPDATE xianyu_kami_item i JOIN xianyu_kami_config c ON c.id = i.kami_config_id SET i.tenant_id = c.tenant_id;
UPDATE xianyu_kami_usage_record r JOIN xianyu_account a ON a.id = r.xianyu_account_id SET r.tenant_id = a.tenant_id;
UPDATE xianyu_keyword_reply_rule r JOIN xianyu_account a ON a.id = r.xianyu_account_id SET r.tenant_id = a.tenant_id;
UPDATE xianyu_keyword_reply_content c JOIN xianyu_keyword_reply_rule r ON r.id = c.rule_id SET c.tenant_id = r.tenant_id;
UPDATE xianyu_goods_sku s JOIN xianyu_account a ON a.id = s.xianyu_account_id SET s.tenant_id = a.tenant_id;
UPDATE xianyu_goods_sku_property p JOIN xianyu_account a ON a.id = p.xianyu_account_id SET p.tenant_id = a.tenant_id;
UPDATE xianyu_human_intervention_record r JOIN xianyu_account a ON a.id = r.xianyu_account_id SET r.tenant_id = a.tenant_id;

ALTER TABLE xianyu_account DROP INDEX uk_account_unb, ADD UNIQUE KEY uk_account_tenant_unb (tenant_id, unb), ADD KEY idx_account_tenant_status (tenant_id, status);
ALTER TABLE xianyu_sys_setting DROP INDEX uk_sys_setting_key, ADD UNIQUE KEY uk_setting_tenant_key (tenant_id, setting_key);
ALTER TABLE xianyu_goods_sku DROP INDEX uk_goods_sku_remote, ADD UNIQUE KEY uk_goods_sku_tenant_remote (tenant_id, xy_goods_id, sku_key);
ALTER TABLE xianyu_goods_sku_property DROP INDEX uk_sku_property_value, ADD UNIQUE KEY uk_sku_property_tenant_value (tenant_id, xy_goods_id, property_id, value_id);

ALTER TABLE xianyu_goods_config
    ADD COLUMN xianyu_auto_rate_on TINYINT NOT NULL DEFAULT 0 AFTER xianyu_auto_reply_on,
    ADD COLUMN xianyu_auto_polish_on TINYINT NOT NULL DEFAULT 0 AFTER xianyu_auto_rate_on,
    ADD COLUMN last_polish_time BIGINT NULL AFTER xianyu_auto_polish_on,
    ADD KEY idx_goods_config_auto_rate (xianyu_auto_rate_on, xianyu_account_id),
    ADD KEY idx_goods_config_auto_polish (xianyu_auto_polish_on, last_polish_time, xianyu_account_id);

ALTER TABLE xianyu_goods_order
    ADD COLUMN rate_status TINYINT NOT NULL DEFAULT 0 AFTER confirm_state,
    ADD COLUMN rate_time DATETIME(3) NULL AFTER rate_status,
    ADD KEY idx_goods_order_rate (tenant_id, rate_status, rate_time);

CREATE TABLE merchant_resource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    xianyu_account_id BIGINT NULL,
    xy_goods_id VARCHAR(100) NULL,
    stock INT NOT NULL DEFAULT 0,
    amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    scheduled_time DATETIME(3) NULL,
    last_run_time DATETIME(3) NULL,
    data_json LONGTEXT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_resource_tenant_type_status (tenant_id, resource_type, status),
    KEY idx_resource_tenant_schedule (tenant_id, resource_type, scheduled_time),
    KEY idx_resource_account (tenant_id, xianyu_account_id),
    CONSTRAINT fk_resource_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE merchant_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    resource_id BIGINT NULL,
    xianyu_account_id BIGINT NULL,
    xy_goods_id VARCHAR(100) NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1执行中 2成功 -1失败',
    scheduled_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_retry_time DATETIME(3) NULL,
    request_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_task_due (status, scheduled_time, next_retry_time),
    KEY idx_task_tenant_type_created (tenant_id, task_type, created_time),
    KEY idx_task_resource (tenant_id, resource_id),
    CONSTRAINT fk_task_resource FOREIGN KEY (resource_id) REFERENCES merchant_resource (id) ON DELETE SET NULL,
    CONSTRAINT fk_task_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE merchant_distribution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    supply_resource_id BIGINT NOT NULL,
    material_resource_id BIGINT NULL,
    xianyu_account_id BIGINT NULL,
    xy_goods_id VARCHAR(100) NULL,
    status TINYINT NOT NULL DEFAULT 0,
    commission_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    settlement_status TINYINT NOT NULL DEFAULT 0,
    settlement_time DATETIME(3) NULL,
    data_json LONGTEXT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_distribution_relation (tenant_id, supply_resource_id, material_resource_id),
    KEY idx_distribution_tenant_status (tenant_id, status, settlement_status),
    KEY idx_distribution_account_goods (tenant_id, xianyu_account_id, xy_goods_id),
    CONSTRAINT fk_distribution_supply FOREIGN KEY (supply_resource_id) REFERENCES merchant_resource (id) ON DELETE RESTRICT,
    CONSTRAINT fk_distribution_material FOREIGN KEY (material_resource_id) REFERENCES merchant_resource (id) ON DELETE SET NULL,
    CONSTRAINT fk_distribution_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE merchant_short_link (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    token VARCHAR(32) NOT NULL,
    target_url VARCHAR(2000) NOT NULL,
    click_count BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_link_token (token),
    KEY idx_short_link_tenant_created (tenant_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 后台线程不带 HTTP 上下文时，按账号关系校正租户归属。
CREATE TRIGGER trg_cookie_tenant BEFORE INSERT ON xianyu_cookie FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_goods_tenant BEFORE INSERT ON xianyu_goods FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_chat_tenant BEFORE INSERT ON xianyu_chat_message FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_goods_config_tenant BEFORE INSERT ON xianyu_goods_config FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_delivery_config_tenant BEFORE INSERT ON xianyu_goods_auto_delivery_config FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_goods_order_tenant BEFORE INSERT ON xianyu_goods_order FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_reply_record_tenant BEFORE INSERT ON xianyu_goods_auto_reply_record FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_operation_log_tenant BEFORE INSERT ON xianyu_operation_log FOR EACH ROW SET NEW.tenant_id = COALESCE((SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id), NEW.tenant_id);
CREATE TRIGGER trg_kami_config_tenant BEFORE INSERT ON xianyu_kami_config FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_kami_item_tenant BEFORE INSERT ON xianyu_kami_item FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_kami_config WHERE id = NEW.kami_config_id);
CREATE TRIGGER trg_kami_usage_tenant BEFORE INSERT ON xianyu_kami_usage_record FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_keyword_rule_tenant BEFORE INSERT ON xianyu_keyword_reply_rule FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_keyword_content_tenant BEFORE INSERT ON xianyu_keyword_reply_content FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_keyword_reply_rule WHERE id = NEW.rule_id);
CREATE TRIGGER trg_sku_tenant BEFORE INSERT ON xianyu_goods_sku FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_sku_property_tenant BEFORE INSERT ON xianyu_goods_sku_property FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
CREATE TRIGGER trg_intervention_tenant BEFORE INSERT ON xianyu_human_intervention_record FOR EACH ROW SET NEW.tenant_id = (SELECT tenant_id FROM xianyu_account WHERE id = NEW.xianyu_account_id);
