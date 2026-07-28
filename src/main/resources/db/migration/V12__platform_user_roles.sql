ALTER TABLE sys_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER' AFTER password;

-- 升级环境保留最早启用的账号作为平台管理员，其余账号保持普通租户权限
UPDATE sys_user
SET role = 'ADMIN'
WHERE id = (
    SELECT seed.id
    FROM (SELECT MIN(id) AS id FROM sys_user WHERE status = 1) seed
);
