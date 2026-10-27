-- 平台SKU编号长度并不固定为32位，放宽列宽，避免多规格同步时因 "Data too long" 回滚整次同步。
-- sku_key 是由 sku_id 生成的列，MySQL 不支持直接修改生成列表达式，只能删除后重建。
ALTER TABLE xianyu_goods_sku DROP INDEX uk_goods_sku_remote;
ALTER TABLE xianyu_goods_sku DROP COLUMN sku_key;
ALTER TABLE xianyu_goods_sku MODIFY COLUMN sku_id VARCHAR(100) NULL;
ALTER TABLE xianyu_goods_sku
    ADD COLUMN sku_key VARCHAR(100) GENERATED ALWAYS AS (COALESCE(sku_id, '')) STORED;
ALTER TABLE xianyu_goods_sku ADD UNIQUE KEY uk_goods_sku_remote (xy_goods_id, sku_key);
