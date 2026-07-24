-- 历史开启状态只在买家评价后触发，迁移为对应的新模式以保持原有行为。
UPDATE xianyu_goods_config
SET xianyu_auto_rate_on = 2
WHERE xianyu_auto_rate_on = 1;
