package com.xianyusmart.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.entity.XianyuSysSetting;
import com.xianyusmart.mapper.XianyuSysSettingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SystemSettingBackupHandler implements DataBackupHandler {

    private static final Set<String> BACKUP_KEYS = Set.of(
            "ai_provider", "ai_protocol", "ai_custom_name", "ai_base_url", "ai_model",
            "ai_embedding_enabled", "ai_embedding_base_url", "ai_embedding_model",
            "ai_image_enabled", "ai_image_base_url", "ai_image_model",
            "sys_prompt", "similarity_threshold",
            "email_smtp_host", "email_smtp_port", "email_smtp_username",
            "email_smtp_from", "email_smtp_ssl",
            "email_notify_ws_disconnect_enabled", "email_notify_cookie_expire_enabled"
    );

    @Autowired
    private XianyuSysSettingMapper sysSettingMapper;

    @Override
    public String getModuleKey() {
        return "systemSetting";
    }

    @Override
    public String getModuleName() {
        return "系统设置";
    }

    @Override
    public Map<String, Object> exportData() {
        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(XianyuSysSetting::getSettingKey, BACKUP_KEYS);
        List<XianyuSysSetting> settings = sysSettingMapper.selectList(wrapper);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", settings);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settingMaps = (List<Map<String, Object>>) data.get("settings");
        if (settingMaps == null) return;

        for (Map<String, Object> map : settingMaps) {
            try {
                String settingKey = (String) map.get("settingKey");
                String settingValue = (String) map.get("settingValue");
                String settingDesc = (String) map.get("settingDesc");

                if (settingKey == null || !BACKUP_KEYS.contains(settingKey)) continue;

                LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuSysSetting::getSettingKey, settingKey);
                XianyuSysSetting existing = sysSettingMapper.selectOne(wrapper);

                if (existing == null) {
                    XianyuSysSetting setting = new XianyuSysSetting();
                    setting.setSettingKey(settingKey);
                    setting.setSettingValue(settingValue);
                    setting.setSettingDesc(settingDesc);
                    sysSettingMapper.insert(setting);
                } else {
                    existing.setSettingValue(settingValue);
                    existing.setSettingDesc(settingDesc);
                    sysSettingMapper.updateById(existing);
                }
            } catch (Exception e) {
                log.warn("[SystemSettingBackup] 导入单条系统设置失败: {}", e.getMessage());
            }
        }
    }
}
