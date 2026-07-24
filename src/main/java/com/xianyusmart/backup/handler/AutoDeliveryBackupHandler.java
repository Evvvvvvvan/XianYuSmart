package com.xianyusmart.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.backup.DataBackupHandler;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuFixedDeliveryTemplateMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.service.BuyerMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class AutoDeliveryBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuFixedDeliveryTemplateMapper fixedTemplateMapper;

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Override
    public String getModuleKey() {
        return "autoDelivery";
    }

    @Override
    public String getModuleName() {
        return "自动发货";
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper.selectList(null);
        List<XianyuFixedDeliveryTemplate> fixedTemplates = fixedTemplateMapper.selectList(null);

        List<Map<String, Object>> result = new ArrayList<>();
        for (XianyuGoodsAutoDeliveryConfig config : configs) {
            XianyuAccount account = accountMapper.selectById(config.getXianyuAccountId());
            if (account == null) continue;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", account.getUnb());
            map.put("xyGoodsId", config.getXyGoodsId());
            map.put("skuId", config.getSkuId());
            map.put("skuName", config.getSkuName());
            map.put("deliveryMode", config.getDeliveryMode());
            XianyuFixedDeliveryTemplate fixedTemplate = config.getFixedTemplateId() == null
                    ? null : fixedTemplateMapper.selectById(config.getFixedTemplateId());
            map.put("fixedTemplateName", fixedTemplate == null ? null : fixedTemplate.getTemplateName());
            map.put("autoDeliveryContent", config.getAutoDeliveryContent());
            map.put("kamiConfigIds", config.getKamiConfigIds());
            map.put("kamiDeliveryTemplate", config.getKamiDeliveryTemplate());
            map.put("deliveryMessageTemplate", config.getDeliveryMessageTemplate());
            map.put("voucherDeliveryEnabled", config.getVoucherDeliveryEnabled());
            map.put("chatDeliveryEnabled", config.getChatDeliveryEnabled());
            map.put("receiptFollowUpMessages", config.getReceiptFollowUpMessages());
            map.put("receiptFollowUpIntervalSeconds", config.getReceiptFollowUpIntervalSeconds());
            map.put("autoDeliveryImageUrl", config.getAutoDeliveryImageUrl());
            map.put("autoConfirmShipment", config.getAutoConfirmShipment());
            result.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> fixedTemplateResult = new ArrayList<>();
        for (XianyuFixedDeliveryTemplate template : fixedTemplates) {
            XianyuAccount account = accountMapper.selectById(template.getXianyuAccountId());
            if (account == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", account.getUnb());
            map.put("templateName", template.getTemplateName());
            map.put("deliveryContent", template.getDeliveryContent());
            map.put("messageTemplate", template.getMessageTemplate());
            fixedTemplateResult.add(map);
        }
        data.put("fixedDeliveryTemplates", fixedTemplateResult);
        data.put("autoDeliveryConfigs", result);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();
        @SuppressWarnings("unchecked")
        Map<String, Long> kamiConfigIdMap = context.get("kamiConfigIdMap") != null
                ? (Map<String, Long>) context.get("kamiConfigIdMap")
                : Collections.emptyMap();

        Map<String, Long> fixedTemplateIds = importFixedTemplates(data, unbToAccountId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("autoDeliveryConfigs");
        if (configMaps == null) return;

        int skippedCount = 0;
        for (Map<String, Object> map : configMaps) {
            try {
                String unb = (String) map.get("unb");
                String xyGoodsId = (String) map.get("xyGoodsId");
                if (unb == null || xyGoodsId == null) continue;

                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    log.warn("[AutoDeliveryBackup] 跳过: 找不到账号, unb={}, xyGoodsId={}", unb, xyGoodsId);
                    skippedCount++;
                    continue;
                }

                String skuId = (String) map.get("skuId");
                LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, accountId)
                       .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, xyGoodsId)
                       .eq(skuId == null, XianyuGoodsAutoDeliveryConfig::getSkuId, null)
                       .eq(skuId != null, XianyuGoodsAutoDeliveryConfig::getSkuId, skuId);
                XianyuGoodsAutoDeliveryConfig existing = autoDeliveryConfigMapper.selectOne(wrapper);

                XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
                config.setXianyuAccountId(accountId);
                config.setXyGoodsId(xyGoodsId);
                config.setSkuId(skuId);
                config.setSkuName((String) map.get("skuName"));
                int deliveryMode = map.get("deliveryMode") != null
                        ? ((Number) map.get("deliveryMode")).intValue() : 1;
                String kamiConfigIds = resolveKamiConfigIds(
                        accountId, (String) map.get("kamiConfigIds"), kamiConfigIdMap);
                deliveryMode = deliveryMode == 2
                        || (deliveryMode == 3 && kamiConfigIds != null && !kamiConfigIds.isBlank()) ? 2 : 1;
                config.setDeliveryMode(deliveryMode);
                Long fixedTemplateId = fixedTemplateIds.get(
                        accountId + ":" + map.get("fixedTemplateName"));
                if (deliveryMode == 1 && fixedTemplateId == null) {
                    fixedTemplateId = importLegacyFixedTemplate(accountId, xyGoodsId, skuId, map);
                }
                // 恢复时只写入当前发货模式所需字段，避免旧备份重新生成组合配置。
                config.setFixedTemplateId(deliveryMode == 1 ? fixedTemplateId : null);
                config.setAutoDeliveryContent(null);
                config.setKamiConfigIds(deliveryMode == 2 ? kamiConfigIds : null);
                config.setKamiDeliveryTemplate(null);
                String deliveryMessageTemplate = (String) map.get("deliveryMessageTemplate");
                config.setDeliveryMessageTemplate(deliveryMode == 2
                        ? mergeLegacyCardTemplates(
                            deliveryMessageTemplate, (String) map.get("kamiDeliveryTemplate"))
                        : null);
                config.setVoucherDeliveryEnabled(map.get("voucherDeliveryEnabled") != null
                        ? ((Number) map.get("voucherDeliveryEnabled")).intValue() : 1);
                config.setChatDeliveryEnabled(map.get("chatDeliveryEnabled") != null
                        ? ((Number) map.get("chatDeliveryEnabled")).intValue() : 1);
                config.setReceiptFollowUpMessages((String) map.get("receiptFollowUpMessages"));
                config.setReceiptFollowUpIntervalSeconds(map.get("receiptFollowUpIntervalSeconds") != null
                        ? ((Number) map.get("receiptFollowUpIntervalSeconds")).intValue() : null);
                config.setAutoDeliveryImageUrl((String) map.get("autoDeliveryImageUrl"));
                config.setAutoConfirmShipment(map.get("autoConfirmShipment") != null ? ((Number) map.get("autoConfirmShipment")).intValue() : null);

                if (existing == null) {
                    autoDeliveryConfigMapper.insert(config);
                } else {
                    config.setId(existing.getId());
                    autoDeliveryConfigMapper.updateById(config);
                }
            } catch (Exception e) {
                log.warn("[AutoDeliveryBackup] 导入单条自动发货配置失败: {}", e.getMessage());
            }
        }
        if (skippedCount > 0) {
            log.warn("[AutoDeliveryBackup] 共跳过 {} 条数据（账号不存在）", skippedCount);
        }
    }

    private String resolveKamiConfigIds(Long accountId, String sourceIds, Map<String, Long> idMap) {
        if (sourceIds == null || sourceIds.isBlank()) {
            return null;
        }
        List<String> resolvedIds = new ArrayList<>();
        for (String sourceId : sourceIds.split(",")) {
            String key = sourceId.trim();
            if (key.isEmpty()) {
                continue;
            }
            Long targetId = idMap.get(key);
            if (targetId == null) {
                targetId = Long.valueOf(key);
            }
            XianyuKamiConfig kamiConfig = kamiConfigMapper.selectById(targetId);
            if (kamiConfig == null || !accountId.equals(kamiConfig.getXianyuAccountId())) {
                throw new IllegalArgumentException("卡密仓库不存在或与商品账号不一致");
            }
            resolvedIds.add(String.valueOf(targetId));
        }
        return resolvedIds.isEmpty() ? null : String.join(",", resolvedIds);
    }

    private String mergeLegacyCardTemplates(String messageTemplate, String itemTemplate) {
        String normalizedMessage = messageTemplate == null || messageTemplate.isBlank()
                ? BuyerMessageService.DEFAULT_DELIVERY_MESSAGE_TEMPLATE : messageTemplate.trim();
        if (!normalizedMessage.contains("{deliveryContent}")) {
            normalizedMessage = normalizedMessage.substring(0, Math.min(982, normalizedMessage.length()))
                    + "\n{deliveryContent}";
        }
        if (itemTemplate == null || itemTemplate.isBlank()) {
            return limitCardTemplate(normalizedMessage);
        }
        String normalizedItem = itemTemplate.contains("{kmKey}")
                ? itemTemplate.replace("{kmKey}", "{deliveryContent}")
                : itemTemplate + "\n{deliveryContent}";
        return limitCardTemplate(normalizedMessage.replace("{deliveryContent}", normalizedItem));
    }

    private String limitCardTemplate(String template) {
        if (template.length() <= 1000) {
            return template;
        }
        String staticContent = template.replace("{deliveryContent}", "");
        return staticContent.substring(0, Math.min(982, staticContent.length()))
                + "\n{deliveryContent}";
    }

    private Map<String, Long> importFixedTemplates(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        Map<String, Long> result = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> templateMaps =
                (List<Map<String, Object>>) data.get("fixedDeliveryTemplates");
        if (templateMaps == null) {
            return result;
        }
        for (Map<String, Object> map : templateMaps) {
            String name = (String) map.get("templateName");
            Long accountId = unbToAccountId.get((String) map.get("unb"));
            if (accountId == null || name == null) continue;
            XianyuFixedDeliveryTemplate template =
                    fixedTemplateMapper.findByAccountIdAndName(accountId, name);
            if (template == null) {
                template = new XianyuFixedDeliveryTemplate();
                template.setXianyuAccountId(accountId);
                template.setTemplateName(name);
            }
            template.setDeliveryContent((String) map.get("deliveryContent"));
            template.setMessageTemplate((String) map.get("messageTemplate"));
            if (template.getId() == null) {
                fixedTemplateMapper.insert(template);
            } else {
                fixedTemplateMapper.updateById(template);
            }
            result.put(accountId + ":" + name, template.getId());
        }
        return result;
    }

    private Long importLegacyFixedTemplate(Long accountId, String xyGoodsId, String skuId,
                                           Map<String, Object> map) {
        String content = (String) map.get("autoDeliveryContent");
        if (content == null || content.isBlank()) {
            return null;
        }
        String suffix = skuId == null ? "" : "-" + skuId;
        String name = "恢复固定模板-" + xyGoodsId + suffix;
        name = name.substring(0, Math.min(name.length(), 100));
        XianyuFixedDeliveryTemplate template =
                fixedTemplateMapper.findByAccountIdAndName(accountId, name);
        if (template == null) {
            template = new XianyuFixedDeliveryTemplate();
            template.setXianyuAccountId(accountId);
            template.setTemplateName(name);
            template.setDeliveryContent(content);
            String messageTemplate = (String) map.get("deliveryMessageTemplate");
            template.setMessageTemplate(messageTemplate == null || messageTemplate.isBlank()
                    ? BuyerMessageService.DEFAULT_DELIVERY_MESSAGE_TEMPLATE : messageTemplate);
            fixedTemplateMapper.insert(template);
        }
        return template.getId();
    }
}
