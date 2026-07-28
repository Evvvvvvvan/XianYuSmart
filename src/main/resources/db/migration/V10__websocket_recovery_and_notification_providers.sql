ALTER TABLE xianyu_account
    ADD COLUMN websocket_sync_pts BIGINT NULL AFTER status,
    ADD COLUMN websocket_sync_seq BIGINT NULL AFTER websocket_sync_pts,
    ADD COLUMN websocket_sync_timestamp BIGINT NULL AFTER websocket_sync_seq;

ALTER TABLE xianyu_notification_channel
    ADD COLUMN channel_type VARCHAR(32) NOT NULL DEFAULT 'WEBHOOK' AFTER channel_name,
    ADD COLUMN config_json TEXT NULL AFTER signing_secret,
    ADD COLUMN message_template VARCHAR(1000) NULL AFTER config_json;
