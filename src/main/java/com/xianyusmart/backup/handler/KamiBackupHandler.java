package com.xianyusmart.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.KamiConfigReqDTO;
import com.xianyusmart.controller.dto.KamiConfigRespDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.service.KamiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class KamiBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private KamiConfigService kamiConfigService;

    @Override
    public String getModuleKey() {
        return "kami";
    }

    @Override
    public String getModuleName() {
        return "卡密仓库";
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuKamiConfig> kamiConfigs = kamiConfigMapper.selectList(null);

        List<Map<String, Object>> configList = new ArrayList<>();
        Map<Long, String> configIdToUnb = new HashMap<>();
        for (XianyuKamiConfig config : kamiConfigs) {
            XianyuAccount account = accountMapper.selectById(config.getXianyuAccountId());
            if (account == null) continue;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sourceId", config.getId());
            map.put("unb", account.getUnb());
            map.put("aliasName", config.getAliasName());
            map.put("sourceType", config.getSourceType());
            map.put("externalApiUrl", config.getExternalApiUrl());
            map.put("externalApiBody", config.getExternalApiBody());
            map.put("externalApiResultPath", config.getExternalApiResultPath());
            map.put("externalApiTimeoutSeconds", config.getExternalApiTimeoutSeconds());
            map.put("alertEnabled", config.getAlertEnabled());
            map.put("alertThresholdType", config.getAlertThresholdType());
            map.put("alertThresholdValue", config.getAlertThresholdValue());
            map.put("alertEmail", config.getAlertEmail());
            configList.add(map);
            configIdToUnb.put(config.getId(), account.getUnb());
        }

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (XianyuKamiConfig config : kamiConfigs) {
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigIdAndStatus(config.getId(), 0);
            String unb = configIdToUnb.get(config.getId());
            if (unb == null) continue;

            for (XianyuKamiItem item : items) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("unb", unb);
                map.put("aliasName", config.getAliasName());
                map.put("kamiContent", item.getKamiContent());
                itemList.add(map);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kamiConfigs", configList);
        data.put("kamiItems", itemList);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        Map<String, Long> configKeyToId = new HashMap<>();
        Map<String, Long> sourceIdToId = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("kamiConfigs");
        if (configMaps != null) {
            int skippedCount = 0;
            for (Map<String, Object> map : configMaps) {
                try {
                    String unb = (String) map.get("unb");
                    String aliasName = (String) map.get("aliasName");
                    if (unb == null || aliasName == null) continue;

                    Long accountId = unbToAccountId.get(unb);
                    if (accountId == null) {
                        log.warn("[KamiBackup] 跳过配置: 找不到账号, unb={}, aliasName={}", unb, aliasName);
                        skippedCount++;
                        continue;
                    }

                    LambdaQueryWrapper<XianyuKamiConfig> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(XianyuKamiConfig::getXianyuAccountId, accountId)
                           .eq(XianyuKamiConfig::getAliasName, aliasName);
                    XianyuKamiConfig existing = kamiConfigMapper.selectOne(wrapper);

                    KamiConfigReqDTO request = new KamiConfigReqDTO();
                    request.setId(existing == null ? null : existing.getId());
                    request.setXianyuAccountId(accountId);
                    request.setAliasName(aliasName);
                    request.setSourceType(map.get("sourceType") == null ? "LOCAL" : (String) map.get("sourceType"));
                    request.setExternalApiUrl((String) map.get("externalApiUrl"));
                    request.setExternalApiBody((String) map.get("externalApiBody"));
                    request.setExternalApiResultPath((String) map.get("externalApiResultPath"));
                    request.setExternalApiTimeoutSeconds(map.get("externalApiTimeoutSeconds") != null
                            ? ((Number) map.get("externalApiTimeoutSeconds")).intValue() : 10);
                    request.setAlertEnabled(map.get("alertEnabled") != null ? ((Number) map.get("alertEnabled")).intValue() : null);
                    request.setAlertThresholdType(map.get("alertThresholdType") != null ? ((Number) map.get("alertThresholdType")).intValue() : null);
                    request.setAlertThresholdValue(map.get("alertThresholdValue") != null ? ((Number) map.get("alertThresholdValue")).intValue() : null);
                    request.setAlertEmail((String) map.get("alertEmail"));

                    // 备份恢复复用配置服务，确保供货中的仓库不会绕过行锁和未结请求校验。
                    ResultObject<KamiConfigRespDTO> saveResult = kamiConfigService.createOrUpdateConfig(request);
                    if (!Integer.valueOf(200).equals(saveResult.getCode()) || saveResult.getData() == null) {
                        throw new IllegalArgumentException(saveResult.getMsg());
                    }
                    Long configId = saveResult.getData().getId();
                    configKeyToId.put(unb + ":" + aliasName, configId);
                    if (map.get("sourceId") != null) {
                        sourceIdToId.put(String.valueOf(map.get("sourceId")), configId);
                    }
                } catch (Exception e) {
                    log.warn("[KamiBackup] 导入单条卡密配置失败: {}", e.getMessage());
                }
            }
            if (skippedCount > 0) {
                log.warn("[KamiBackup] 共跳过 {} 条配置数据（账号不存在）", skippedCount);
            }
        }
        context.put("kamiConfigIdMap", sourceIdToId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) data.get("kamiItems");
        if (itemMaps != null) {
            int skippedCount = 0;
            for (Map<String, Object> map : itemMaps) {
                try {
                    String unb = (String) map.get("unb");
                    String aliasName = (String) map.get("aliasName");
                    String kamiContent = (String) map.get("kamiContent");
                    if (unb == null || aliasName == null || kamiContent == null) continue;

                    Long configId = configKeyToId.get(unb + ":" + aliasName);
                    if (configId == null) {
                        skippedCount++;
                        continue;
                    }

                    LambdaQueryWrapper<XianyuKamiItem> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(XianyuKamiItem::getKamiConfigId, configId)
                           .eq(XianyuKamiItem::getKamiContent, kamiContent);
                    XianyuKamiItem existing = kamiItemMapper.selectOne(wrapper);
                    if (existing != null) continue;

                    XianyuKamiItem item = new XianyuKamiItem();
                    item.setKamiConfigId(configId);
                    item.setKamiContent(kamiContent);
                    item.setStatus(0);
                    item.setSortOrder(0);
                    kamiItemMapper.insert(item);
                } catch (Exception e) {
                    log.warn("[KamiBackup] 导入单条卡密项失败: {}", e.getMessage());
                }
            }
            if (skippedCount > 0) {
                log.warn("[KamiBackup] 共跳过 {} 条卡密项数据（配置不存在）", skippedCount);
            }
        }
    }
}
