CREATE TABLE xianyu_fixed_delivery_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    template_name VARCHAR(100) NOT NULL,
    delivery_content TEXT NOT NULL,
    message_template VARCHAR(1000) NOT NULL,
    source_config_id BIGINT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fixed_template_account_name (tenant_id, xianyu_account_id, template_name),
    UNIQUE KEY uk_fixed_template_source_config (source_config_id),
    KEY idx_fixed_template_account (tenant_id, xianyu_account_id),
    CONSTRAINT fk_fixed_template_account FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE xianyu_goods_auto_delivery_config
    ADD COLUMN fixed_template_id BIGINT NULL AFTER delivery_mode,
    ADD COLUMN voucher_delivery_enabled TINYINT NOT NULL DEFAULT 1 AFTER delivery_message_template,
    ADD COLUMN chat_delivery_enabled TINYINT NOT NULL DEFAULT 1 AFTER voucher_delivery_enabled,
    ADD KEY idx_delivery_config_fixed_template (fixed_template_id),
    ADD CONSTRAINT fk_delivery_config_fixed_template FOREIGN KEY (fixed_template_id)
        REFERENCES xianyu_fixed_delivery_template (id) ON DELETE RESTRICT;

-- 将历史固定内容转换为账号级模板，确保升级后原有商品仍可继续使用。
INSERT INTO xianyu_fixed_delivery_template (
    tenant_id, xianyu_account_id, template_name, delivery_content, message_template, source_config_id
)
SELECT
    tenant_id,
    xianyu_account_id,
    CONCAT('历史固定模板-', id),
    TRIM(auto_delivery_content),
    COALESCE(NULLIF(TRIM(delivery_message_template), ''), '您好，{buyerName}，订单 {orderId} 已发货：\n{deliveryContent}'),
    id
FROM xianyu_goods_auto_delivery_config
WHERE (delivery_mode & 1) = 1
  AND auto_delivery_content IS NOT NULL
  AND TRIM(auto_delivery_content) <> '';

UPDATE xianyu_goods_auto_delivery_config config
JOIN xianyu_fixed_delivery_template template ON template.source_config_id = config.id
SET config.fixed_template_id = template.id;

-- 历史组合模式优先保留卡密；未绑定卡密仓库时保留固定内容。
UPDATE xianyu_goods_auto_delivery_config
SET delivery_mode = CASE
    WHEN delivery_mode = 2 THEN 2
    WHEN delivery_mode = 3 AND kami_config_ids IS NOT NULL AND TRIM(kami_config_ids) <> '' THEN 2
    ELSE 1
END;

-- 卡密仅保留一套最终模板，历史空模板直接发送实际分配的卡密内容。
UPDATE xianyu_goods_auto_delivery_config
SET delivery_message_template = COALESCE(NULLIF(TRIM(delivery_message_template), ''), '{deliveryContent}')
WHERE delivery_mode = 2;

UPDATE xianyu_goods_auto_delivery_config
SET delivery_message_template = CONCAT(LEFT(delivery_message_template, 982), '\n{deliveryContent}')
WHERE delivery_mode = 2
  AND LOCATE('{deliveryContent}', delivery_message_template) = 0;

-- 将旧版逐卡模板折叠进最终模板，单卡订单升级前后文案保持一致。
UPDATE xianyu_goods_auto_delivery_config
SET delivery_message_template = CASE
    WHEN CHAR_LENGTH(REPLACE(
            delivery_message_template,
            '{deliveryContent}',
            CASE
                WHEN LOCATE('{kmKey}', kami_delivery_template) > 0
                    THEN REPLACE(kami_delivery_template, '{kmKey}', '{deliveryContent}')
                ELSE CONCAT(kami_delivery_template, '\n{deliveryContent}')
            END
        )) <= 1000
        THEN REPLACE(
            delivery_message_template,
            '{deliveryContent}',
            CASE
                WHEN LOCATE('{kmKey}', kami_delivery_template) > 0
                    THEN REPLACE(kami_delivery_template, '{kmKey}', '{deliveryContent}')
                ELSE CONCAT(kami_delivery_template, '\n{deliveryContent}')
            END
        )
    ELSE CONCAT(
        LEFT(REPLACE(REPLACE(
            delivery_message_template,
            '{deliveryContent}',
            CASE
                WHEN LOCATE('{kmKey}', kami_delivery_template) > 0
                    THEN REPLACE(kami_delivery_template, '{kmKey}', '{deliveryContent}')
                ELSE CONCAT(kami_delivery_template, '\n{deliveryContent}')
            END
        ), '{deliveryContent}', ''), 982),
        '\n{deliveryContent}'
    )
END
WHERE delivery_mode = 2
  AND kami_delivery_template IS NOT NULL
  AND TRIM(kami_delivery_template) <> '';

UPDATE xianyu_goods_auto_delivery_config
SET
    fixed_template_id = NULL,
    auto_delivery_content = NULL,
    kami_delivery_template = NULL
WHERE delivery_mode = 2;

-- 固定内容仅保留模板关联，清理历史卡密字段，保证两种模式在数据层完全互斥。
UPDATE xianyu_goods_auto_delivery_config
SET auto_delivery_content = NULL,
    kami_config_ids = NULL,
    kami_delivery_template = NULL,
    delivery_message_template = NULL
WHERE delivery_mode = 1;

DELETE template
FROM xianyu_fixed_delivery_template template
JOIN xianyu_goods_auto_delivery_config config ON config.id = template.source_config_id
WHERE config.delivery_mode = 2;

ALTER TABLE xianyu_fixed_delivery_template
    DROP INDEX uk_fixed_template_source_config,
    DROP COLUMN source_config_id;

ALTER TABLE xianyu_goods_auto_delivery_config
    ADD CONSTRAINT chk_delivery_mode_exclusive CHECK (delivery_mode IN (1, 2)),
    ADD CONSTRAINT chk_delivery_channel_enabled CHECK (
        voucher_delivery_enabled = 1 OR chat_delivery_enabled = 1
    );
