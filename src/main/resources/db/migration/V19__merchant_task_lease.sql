-- 运营任务需要租约归属，避免任务被重新领取后旧执行器仍写入完成/失败结果。
ALTER TABLE merchant_task
    ADD COLUMN lease_owner VARCHAR(100) NULL AFTER attempt_count,
    ADD COLUMN lease_expire_time DATETIME(3) NULL AFTER lease_owner;

-- 历史执行中的任务没有租约信息，按原 10 分钟启发式视为可回收。
UPDATE merchant_task SET lease_owner = NULL, lease_expire_time = NULL WHERE status = 1;
