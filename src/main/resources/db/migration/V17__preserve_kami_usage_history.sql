-- 使用记录保存完整交付快照，删除库存卡密时只解除关联并保留历史审计。
ALTER TABLE xianyu_kami_usage_record
    DROP FOREIGN KEY fk_usage_item,
    MODIFY COLUMN kami_item_id BIGINT NULL,
    ADD CONSTRAINT fk_usage_item_history FOREIGN KEY (kami_item_id)
        REFERENCES xianyu_kami_item (id) ON DELETE SET NULL;
