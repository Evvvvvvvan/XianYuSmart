-- 平台SKU编号长度并不固定为32位，放宽列宽，避免多规格同步时因 "Data too long" 回滚整次同步。
-- sku_key 是由 sku_id 生成的列，MySQL 不支持直接修改生成列表达式，只能删除后重建。
-- 唯一键名称随版本变化（V1 为 uk_goods_sku_remote，V3 起为 uk_goods_sku_tenant_remote），
-- 这里按 information_schema 中实际存在的索引动态删除，避免不同历史版本的库在升级时启动失败。
SET @sku_index_name := (
    SELECT INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'xianyu_goods_sku'
      AND COLUMN_NAME = 'sku_key'
      AND NON_UNIQUE = 0
    LIMIT 1
);
SET @drop_sku_index := IF(
    @sku_index_name IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE xianyu_goods_sku DROP INDEX `', @sku_index_name, '`')
);
PREPARE drop_sku_index_stmt FROM @drop_sku_index;
EXECUTE drop_sku_index_stmt;
DEALLOCATE PREPARE drop_sku_index_stmt;

ALTER TABLE xianyu_goods_sku DROP COLUMN sku_key;
ALTER TABLE xianyu_goods_sku MODIFY COLUMN sku_id VARCHAR(100) NULL;
ALTER TABLE xianyu_goods_sku
    ADD COLUMN sku_key VARCHAR(100) GENERATED ALWAYS AS (COALESCE(sku_id, '')) STORED;
-- 按多租户维度重建唯一键，与 V3 之后的表结构保持一致。
ALTER TABLE xianyu_goods_sku
    ADD UNIQUE KEY uk_goods_sku_tenant_remote (tenant_id, xy_goods_id, sku_key);
