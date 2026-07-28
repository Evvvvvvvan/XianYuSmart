CREATE TABLE sys_user_permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_permission (user_id, permission_code),
    KEY idx_user_permission_code (permission_code),
    CONSTRAINT fk_user_permission_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 现有普通账号默认保留原有全部能力，升级后不改变使用习惯。
INSERT INTO sys_user_permission (user_id, permission_code)
SELECT users.id, permissions.permission_code
FROM sys_user users
CROSS JOIN (
    SELECT 'menu:dashboard' permission_code UNION ALL
    SELECT 'menu:accounts' UNION ALL
    SELECT 'menu:connection' UNION ALL
    SELECT 'menu:goods' UNION ALL
    SELECT 'menu:operations' UNION ALL
    SELECT 'menu:messages' UNION ALL
    SELECT 'menu:buyers' UNION ALL
    SELECT 'menu:kami' UNION ALL
    SELECT 'menu:fixed-delivery' UNION ALL
    SELECT 'menu:auto-delivery' UNION ALL
    SELECT 'menu:orders' UNION ALL
    SELECT 'menu:auto-reply' UNION ALL
    SELECT 'menu:operation-log' UNION ALL
    SELECT 'menu:health' UNION ALL
    SELECT 'menu:settings' UNION ALL
    SELECT 'action:account-write' UNION ALL
    SELECT 'action:connection-write' UNION ALL
    SELECT 'action:goods-write' UNION ALL
    SELECT 'action:operations-write' UNION ALL
    SELECT 'action:message-send' UNION ALL
    SELECT 'action:buyer-write' UNION ALL
    SELECT 'action:delivery-write' UNION ALL
    SELECT 'action:order-write' UNION ALL
    SELECT 'action:automation-write' UNION ALL
    SELECT 'action:system-write'
) permissions
WHERE users.role = 'USER';
