ALTER TABLE xianyu_goods_auto_delivery_config
    ADD COLUMN delivery_message_template VARCHAR(1000) NULL AFTER kami_delivery_template,
    ADD COLUMN receipt_follow_up_messages TEXT NULL AFTER delivery_message_template,
    ADD COLUMN receipt_follow_up_interval_seconds INT NOT NULL DEFAULT 5 AFTER receipt_follow_up_messages;

ALTER TABLE xianyu_goods_order
    ADD COLUMN sku_id VARCHAR(32) NULL AFTER sku_name,
    ADD COLUMN delivery_message_content TEXT NULL AFTER content,
    ADD COLUMN delivery_message_state TINYINT NOT NULL DEFAULT 0 AFTER delivery_message_content,
    ADD COLUMN delivery_message_attempt_count INT NOT NULL DEFAULT 0 AFTER delivery_message_state,
    ADD COLUMN delivery_message_next_retry_time DATETIME(3) NULL AFTER delivery_message_attempt_count,
    ADD COLUMN buyer_confirmed_receipt TINYINT NOT NULL DEFAULT 0 AFTER confirm_state,
    ADD COLUMN receipt_follow_up_sent_count INT NOT NULL DEFAULT 0 AFTER buyer_confirmed_receipt,
    ADD COLUMN receipt_follow_up_next_time DATETIME(3) NULL AFTER receipt_follow_up_sent_count,
    ADD COLUMN receipt_follow_up_completed TINYINT NOT NULL DEFAULT 0 AFTER receipt_follow_up_next_time,
    ADD KEY idx_goods_order_delivery_message (delivery_message_state, delivery_message_next_retry_time),
    ADD KEY idx_goods_order_receipt_follow_up (receipt_follow_up_completed, receipt_follow_up_next_time);

-- 历史订单不补发新话术，仅新订单进入确认收货后的发送流程。
UPDATE xianyu_goods_order SET receipt_follow_up_completed = 1;
