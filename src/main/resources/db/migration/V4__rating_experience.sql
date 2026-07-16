ALTER TABLE xianyu_goods_config
    ADD COLUMN xianyu_auto_rate_content VARCHAR(500) NOT NULL DEFAULT '交易愉快，感谢支持，期待再次合作。满意的话欢迎点亮小红花。' AFTER xianyu_auto_rate_on;

ALTER TABLE xianyu_goods_order
    ADD COLUMN rate_content VARCHAR(500) NULL AFTER rate_time,
    ADD COLUMN rate_source VARCHAR(16) NULL AFTER rate_content;
