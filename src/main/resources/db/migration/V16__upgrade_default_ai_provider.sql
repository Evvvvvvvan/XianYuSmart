-- 仅迁移未变更的旧版AI默认配置，保留已自定义的服务地址和模型。
CREATE TEMPORARY TABLE tmp_v16_ai_default_tenant (
    tenant_id BIGINT NOT NULL PRIMARY KEY
);

INSERT INTO tmp_v16_ai_default_tenant (tenant_id)
SELECT base_setting.tenant_id
FROM xianyu_sys_setting base_setting
JOIN xianyu_sys_setting model_setting
  ON model_setting.tenant_id = base_setting.tenant_id
 AND model_setting.setting_key = 'ai_model'
LEFT JOIN xianyu_sys_setting provider_setting
  ON provider_setting.tenant_id = base_setting.tenant_id
 AND provider_setting.setting_key = 'ai_provider'
LEFT JOIN xianyu_sys_setting protocol_setting
  ON protocol_setting.tenant_id = base_setting.tenant_id
 AND protocol_setting.setting_key = 'ai_protocol'
WHERE base_setting.setting_key = 'ai_base_url'
  AND base_setting.setting_value = 'https://dashscope.aliyuncs.com/compatible-mode'
  AND model_setting.setting_value = 'deepseek-v3'
  AND provider_setting.id IS NULL
  AND protocol_setting.id IS NULL;

INSERT INTO xianyu_sys_setting (tenant_id, setting_key, setting_value, setting_desc)
SELECT candidate.tenant_id, 'ai_provider', 'deepseek', 'AI服务提供商'
FROM tmp_v16_ai_default_tenant candidate;

INSERT INTO xianyu_sys_setting (tenant_id, setting_key, setting_value, setting_desc)
SELECT candidate.tenant_id, 'ai_protocol', 'openai', 'AI服务接口协议'
FROM tmp_v16_ai_default_tenant candidate;

UPDATE xianyu_sys_setting base_setting
JOIN tmp_v16_ai_default_tenant candidate
  ON candidate.tenant_id = base_setting.tenant_id
JOIN xianyu_sys_setting model_setting
  ON model_setting.tenant_id = candidate.tenant_id
 AND model_setting.setting_key = 'ai_model'
SET base_setting.setting_value = 'https://api.deepseek.com',
    base_setting.setting_desc = 'AI服务的API Base URL',
    model_setting.setting_value = 'deepseek-v4-flash',
    model_setting.setting_desc = 'AI对话模型名称'
WHERE base_setting.setting_key = 'ai_base_url';

DROP TEMPORARY TABLE tmp_v16_ai_default_tenant;
