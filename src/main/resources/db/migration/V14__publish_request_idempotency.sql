ALTER TABLE merchant_task
    ADD COLUMN request_key VARCHAR(64) NULL AFTER task_type,
    ADD UNIQUE KEY uk_task_tenant_type_request (tenant_id, task_type, request_key);
