package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.entity.XianyuKamiUsageRecord;
import com.xianyusmart.enums.KamiStatus;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiExternalRequestMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.mapper.XianyuKamiUsageRecordMapper;
import com.xianyusmart.service.EmailNotifyService;
import com.xianyusmart.service.ExternalKamiProvisionService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.notification.WebhookSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KamiConfigServiceImpl implements KamiConfigService {

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiExternalRequestMapper kamiExternalRequestMapper;

    @Autowired
    private XianyuAccountMapper xianyuAccountMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private ExternalKamiProvisionService externalKamiProvisionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private NotificationCenterService notificationCenterService;

    private final ConcurrentHashMap<Long, Long> stockOutEmailSentTime = new ConcurrentHashMap<>();

    private static final long STOCK_OUT_EMAIL_INTERVAL_MS = 10 * 60 * 1000L;

    @Override
    @Transactional
    public ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO) {
        try {
            validateSourceConfig(reqDTO);
            XianyuKamiConfig config;
            if (reqDTO.getId() != null) {
                config = kamiConfigMapper.lockById(reqDTO.getId());
                if (config == null) {
                    return ResultObject.failed("卡密配置不存在");
                }
                if (supplyConfigurationChanged(config, reqDTO) && hasUnsettledSupply(config.getId())) {
                    return ResultObject.failed("存在预占库存或待核对供货请求，暂不能修改供货来源");
                }
            } else {
                // 创建前按租户校验账号归属，避免配置挂载到其他租户账号。
                if (reqDTO.getXianyuAccountId() == null
                        || xianyuAccountMapper.selectById(reqDTO.getXianyuAccountId()) == null) {
                    return ResultObject.failed("闲鱼账号不存在或无权访问");
                }
                config = new XianyuKamiConfig();
                config.setXianyuAccountId(reqDTO.getXianyuAccountId());
                config.setTotalCount(0);
                config.setUsedCount(0);
            }
            if (reqDTO.getAliasName() != null) {
                config.setAliasName(reqDTO.getAliasName());
            }
            config.setSourceType(reqDTO.getSourceType() == null ? "LOCAL" : reqDTO.getSourceType().trim().toUpperCase());
            config.setExternalApiUrl(reqDTO.getExternalApiUrl());
            if (reqDTO.getId() == null || (reqDTO.getExternalApiHeaders() != null
                    && !reqDTO.getExternalApiHeaders().isBlank())) {
                config.setExternalApiHeaders(reqDTO.getExternalApiHeaders());
            }
            config.setExternalApiBody(reqDTO.getExternalApiBody());
            config.setExternalApiResultPath(reqDTO.getExternalApiResultPath());
            config.setExternalApiTimeoutSeconds(reqDTO.getExternalApiTimeoutSeconds() == null
                    ? 10 : reqDTO.getExternalApiTimeoutSeconds());
            if (reqDTO.getAlertEnabled() != null) {
                config.setAlertEnabled(reqDTO.getAlertEnabled());
            }
            if (reqDTO.getAlertThresholdType() != null) {
                config.setAlertThresholdType(reqDTO.getAlertThresholdType());
            }
            if (reqDTO.getAlertThresholdValue() != null) {
                config.setAlertThresholdValue(reqDTO.getAlertThresholdValue());
            }
            if (reqDTO.getAlertEmail() != null) {
                config.setAlertEmail(reqDTO.getAlertEmail());
            }
            if (reqDTO.getId() != null) {
                kamiConfigMapper.updateById(config);
            } else {
                kamiConfigMapper.insert(config);
            }
            return ResultObject.success(toConfigRespDTO(config));
        } catch (Exception e) {
            log.error("创建/更新卡密配置失败", e);
            return ResultObject.failed("创建/更新卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId) {
        try {
            List<XianyuKamiConfig> configs = kamiConfigMapper.findByAccountId(xianyuAccountId);
            List<KamiConfigRespDTO> result = configs.stream()
                    .map(this::toConfigRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密配置列表失败", e);
            return ResultObject.failed("查询卡密配置列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<KamiConfigRespDTO> getConfigById(Long id) {
        try {
            XianyuKamiConfig config = kamiConfigMapper.selectById(id);
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            return ResultObject.success(toConfigRespDTO(config));
        } catch (Exception e) {
            log.error("查询卡密配置失败", e);
            return ResultObject.failed("查询卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> deleteConfig(Long id) {
        try {
            XianyuKamiConfig config = kamiConfigMapper.lockById(id);
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigId(id);
            // 预占库存和待复核外部请求必须先完成处理，避免删除后丢失供应审计链路。
            if (hasUnsettledSupply(id)) {
                return ResultObject.failed("存在预占库存或待核对供货请求，请处理后再删除");
            }
            for (XianyuKamiItem item : items) {
                kamiItemMapper.deleteById(item.getId());
            }
            kamiConfigMapper.deleteById(id);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("删除卡密配置失败", e);
            return ResultObject.failed("删除卡密配置失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO) {
        try {
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            if ("API".equalsIgnoreCase(config.getSourceType())) {
                return ResultObject.failed("外部接口卡密仓库不支持手动添加库存");
            }
            XianyuKamiItem item = new XianyuKamiItem();
            item.setKamiConfigId(reqDTO.getKamiConfigId());
            String content = reqDTO.getKamiContent().trim();
            item.setKamiContent(content);
            item.setStatus(0);
            item.setSortOrder(kamiItemMapper.countByConfigId(reqDTO.getKamiConfigId()));

            boolean duplicated = kamiItemMapper.countByConfigIdAndContent(reqDTO.getKamiConfigId(), content) > 0;
            kamiItemMapper.insert(item);
            refreshConfigCounts(reqDTO.getKamiConfigId());

            if (duplicated) {
                return ResultObject.success(toItemRespDTO(item), "卡密内容重复，已导入");
            }
            return ResultObject.success(toItemRespDTO(item));
        } catch (Exception e) {
            log.error("添加卡密失败", e);
            return ResultObject.failed("添加卡密失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO) {
        try {
            XianyuKamiConfig config = kamiConfigMapper.selectById(reqDTO.getKamiConfigId());
            if (config == null) {
                return ResultObject.failed("卡密配置不存在");
            }
            if ("API".equalsIgnoreCase(config.getSourceType())) {
                return ResultObject.failed("外部接口卡密仓库不支持批量导入库存");
            }
            String[] lines = reqDTO.getKamiContents().split("\\r?\\n");
            int baseOrder = kamiItemMapper.countByConfigId(reqDTO.getKamiConfigId());
            int added = 0;
            int duplicated = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                boolean dup = kamiItemMapper.countByConfigIdAndContent(reqDTO.getKamiConfigId(), trimmed) > 0;
                if (dup) duplicated++;

                XianyuKamiItem item = new XianyuKamiItem();
                item.setKamiConfigId(reqDTO.getKamiConfigId());
                item.setKamiContent(trimmed);
                item.setStatus(0);
                item.setSortOrder(baseOrder + added);
                kamiItemMapper.insert(item);
                added++;
            }
            refreshConfigCounts(reqDTO.getKamiConfigId());
            String msg = duplicated > 0
                    ? String.format("成功导入%d条，其中重复%d条", added, duplicated)
                    : String.format("成功导入%d条", added);
            return ResultObject.success(added, msg);
        } catch (Exception e) {
            log.error("批量导入卡密失败", e);
            return ResultObject.failed("批量导入卡密失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId) {
        try {
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigId(kamiConfigId);
            List<KamiItemRespDTO> result = items.stream()
                    .map(this::toItemRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO) {
        try {
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigIdWithFilter(
                    reqDTO.getKamiConfigId(), 
                    reqDTO.getStatus(), 
                    reqDTO.getKeyword());
            List<KamiItemRespDTO> result = items.stream()
                    .map(this::toItemRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> deleteKamiItem(Long id) {
        try {
            XianyuKamiItem item = kamiItemMapper.selectById(id);
            if (item == null) {
                return ResultObject.failed("卡密不存在");
            }
            kamiItemMapper.deleteById(id);
            refreshConfigCounts(item.getKamiConfigId());
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("删除卡密失败", e);
            return ResultObject.failed("删除卡密失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ResultObject<Void> resetKamiItem(Long id) {
        try {
            int rows = kamiItemMapper.markUnused(id);
            if (rows == 0) {
                return ResultObject.failed("卡密状态重置失败，可能已是未使用状态");
            }
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("重置卡密状态失败", e);
            return ResultObject.failed("重置卡密状态失败: " + e.getMessage());
        }
    }

    @Override
    public XianyuKamiItem acquireKami(Long kamiConfigId, String orderId) {
        try {
            List<XianyuKamiItem> items = reserveKami(kamiConfigId, orderId, 1);
            return items.isEmpty() ? null : items.getFirst();
        } catch (BusinessException e) {
            return null;
        }
    }

    @Override
    public List<XianyuKamiItem> reserveKami(Long kamiConfigId, String orderId, int quantity) {
        if (kamiConfigId == null || orderId == null || orderId.isBlank() || quantity < 1) {
            throw new BusinessException(400, "卡密预占参数无效");
        }

        XianyuKamiConfig sourceConfig = kamiConfigMapper.selectById(kamiConfigId);
        if (sourceConfig == null) {
            throw new BusinessException(404, "卡密配置不存在");
        }
        if ("API".equalsIgnoreCase(sourceConfig.getSourceType())) {
            return externalKamiProvisionService.reserve(sourceConfig, orderId, quantity);
        }
        List<XianyuKamiItem> reserved = new TransactionTemplate(transactionManager).execute(status ->
                reserveLocalKami(kamiConfigId, orderId, quantity));
        if (reserved == null) {
            throw new BusinessException(409, "卡密预占失败");
        }
        return reserved;
    }

    private List<XianyuKamiItem> reserveLocalKami(Long kamiConfigId, String orderId, int quantity) {
        // 先按订单全局锁定已有卡密，防止多仓库回退或并发重试为同一订单换发卡密。
        List<XianyuKamiItem> existing = kamiItemMapper.lockReservedByOrder(orderId);
        if (!existing.isEmpty()) {
            boolean allReserved = existing.stream()
                    .allMatch(item -> item.getStatus() == KamiStatus.RESERVED.getCode());
            if (!allReserved) {
                throw new BusinessException(409, "订单卡密已交付或正在待核对");
            }
            if (existing.size() == quantity) {
                return existing;
            }
            throw new BusinessException(409, "订单卡密数量与已有预占不一致");
        }

        // 同一卡密库短事务串行预占，避免同订单在租约交叠时重复取卡。
        XianyuKamiConfig config = kamiConfigMapper.lockById(kamiConfigId);
        if (config == null) {
            throw new BusinessException(404, "卡密配置不存在");
        }

        List<XianyuKamiItem> items = kamiItemMapper.lockAvailable(kamiConfigId, quantity);
        if (items.size() != quantity) {
            sendStockOutEmailIfNeeded(config, kamiConfigId, orderId);
            throw new BusinessException(409, "卡密库存不足");
        }

        List<Long> itemIds = items.stream().map(XianyuKamiItem::getId).toList();
        if (kamiItemMapper.reserve(itemIds, orderId) != quantity) {
            throw new BusinessException(409, "卡密预占冲突");
        }
        items.forEach(item -> {
            item.setStatus(KamiStatus.RESERVED.getCode());
            item.setOrderId(orderId);
        });
        return items;
    }

    @Override
    @Transactional
    public void commitReservation(String orderId, Long accountId, String xyGoodsId,
                                  String buyerUserId, String buyerUserName) {
        List<XianyuKamiItem> reservedItems = kamiItemMapper.findByOrderAndStatus(
                orderId, KamiStatus.RESERVED.getCode());
        if (reservedItems.isEmpty()) {
            return;
        }

        if (kamiItemMapper.commitReservation(orderId) != reservedItems.size()) {
            throw new BusinessException(409, "卡密交付提交冲突");
        }

        for (int index = 0; index < reservedItems.size(); index++) {
            XianyuKamiItem item = reservedItems.get(index);
            XianyuKamiUsageRecord usageRecord = new XianyuKamiUsageRecord();
            usageRecord.setKamiConfigId(item.getKamiConfigId());
            usageRecord.setKamiItemId(item.getId());
            usageRecord.setXianyuAccountId(accountId);
            usageRecord.setXyGoodsId(xyGoodsId);
            usageRecord.setOrderId(orderId);
            usageRecord.setDeliveryIndex(index + 1);
            usageRecord.setDeliveryStatus(KamiStatus.DELIVERED.name());
            usageRecord.setBuyerUserId(buyerUserId);
            usageRecord.setBuyerUserName(buyerUserName);
            usageRecord.setKamiContent(item.getKamiContent());
            kamiUsageRecordMapper.insert(usageRecord);
        }

        reservedItems.stream().map(XianyuKamiItem::getKamiConfigId).distinct().forEach(configId -> {
            refreshConfigCounts(configId);
            XianyuKamiConfig config = kamiConfigMapper.selectById(configId);
            if (config != null) {
                checkAndSendAlert(config, configId);
            }
        });
    }

    @Override
    @Transactional
    public void releaseReservation(String orderId) {
        if (orderId != null && !orderId.isBlank()) {
            kamiItemMapper.releaseReservation(orderId);
        }
    }

    @Override
    @Transactional
    public void markReservationReviewRequired(String orderId) {
        if (orderId != null && !orderId.isBlank()) {
            kamiItemMapper.markReservationReviewRequired(orderId);
        }
    }

    private void sendStockOutEmailIfNeeded(XianyuKamiConfig config, Long kamiConfigId, String orderId) {
        Long lastSentTime = stockOutEmailSentTime.get(kamiConfigId);
        long now = System.currentTimeMillis();
        if (lastSentTime != null && (now - lastSentTime) < STOCK_OUT_EMAIL_INTERVAL_MS) {
            log.debug("卡密库存不足邮件10分钟内已发送过，跳过: configId={}", kamiConfigId);
            return;
        }
        stockOutEmailSentTime.put(kamiConfigId, now);
        String configName = config.getAliasName() != null ? config.getAliasName() : "卡密配置" + kamiConfigId;
        notificationCenterService.dispatch("KAMI_STOCK_LOW", config.getXianyuAccountId(),
                "卡密库存不足", configName + " 已无可用卡密",
                Map.of("configId", kamiConfigId, "orderId", orderId == null ? "" : orderId));
        emailNotifyService.sendKamiStockOutEmail(config.getAlertEmail(), configName, orderId);
    }

    @Override
    public XianyuKamiConfig getConfig(Long kamiConfigId) {
        return kamiConfigMapper.selectById(kamiConfigId);
    }

    @Override
    public ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO) {
        try {
            List<XianyuKamiItem> items = new ArrayList<>();
            boolean includeUnused = reqDTO.getIncludeUnused() != null && reqDTO.getIncludeUnused();
            boolean includeUsed = reqDTO.getIncludeUsed() != null && reqDTO.getIncludeUsed();

            if (includeUnused && includeUsed) {
                items = kamiItemMapper.findByConfigId(reqDTO.getKamiConfigId());
            } else if (includeUnused) {
                items = kamiItemMapper.findByConfigIdAndStatus(reqDTO.getKamiConfigId(), 0);
            } else if (includeUsed) {
                items = kamiItemMapper.findByConfigIdAndStatus(reqDTO.getKamiConfigId(), 1);
            }

            List<KamiItemRespDTO> result = items.stream()
                    .map(this::toItemRespDTO)
                    .collect(Collectors.toList());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("导出卡密失败", e);
            return ResultObject.failed("导出卡密失败: " + e.getMessage());
        }
    }

    private void refreshConfigCounts(Long kamiConfigId) {
        int total = kamiItemMapper.countByConfigId(kamiConfigId);
        int used = kamiItemMapper.countUsed(kamiConfigId);
        XianyuKamiConfig config = kamiConfigMapper.selectById(kamiConfigId);
        if (config != null) {
            config.setTotalCount(total);
            config.setUsedCount(used);
            kamiConfigMapper.updateById(config);
        }
    }

    private KamiConfigRespDTO toConfigRespDTO(XianyuKamiConfig config) {
        KamiConfigRespDTO dto = new KamiConfigRespDTO();
        dto.setId(config.getId());
        dto.setXianyuAccountId(config.getXianyuAccountId());
        dto.setAliasName(config.getAliasName());
        dto.setSourceType(config.getSourceType());
        dto.setExternalApiUrl(config.getExternalApiUrl());
        dto.setExternalApiHeadersConfigured(config.getExternalApiHeaders() != null
                && !config.getExternalApiHeaders().isBlank());
        dto.setExternalApiBody(config.getExternalApiBody());
        dto.setExternalApiResultPath(config.getExternalApiResultPath());
        dto.setExternalApiTimeoutSeconds(config.getExternalApiTimeoutSeconds());
        dto.setAlertEnabled(config.getAlertEnabled());
        dto.setAlertThresholdType(config.getAlertThresholdType());
        dto.setAlertThresholdValue(config.getAlertThresholdValue());
        dto.setAlertEmail(config.getAlertEmail());
        dto.setTotalCount(config.getTotalCount());
        dto.setUsedCount(config.getUsedCount());
        int unused = kamiItemMapper.countUnused(config.getId());
        dto.setAvailableCount(unused);
        dto.setCreateTime(config.getCreateTime());
        dto.setUpdateTime(config.getUpdateTime());
        return dto;
    }

    private KamiItemRespDTO toItemRespDTO(XianyuKamiItem item) {
        KamiItemRespDTO dto = new KamiItemRespDTO();
        dto.setId(item.getId());
        dto.setKamiConfigId(item.getKamiConfigId());
        dto.setKamiContent(item.getKamiContent());
        dto.setStatus(item.getStatus());
        dto.setOrderId(item.getOrderId());
        dto.setUsedTime(item.getUsedTime());
        dto.setSortOrder(item.getSortOrder());
        dto.setCreateTime(item.getCreateTime());
        return dto;
    }

    private void checkAndSendAlert(XianyuKamiConfig config, Long kamiConfigId) {
        if (config == null || config.getAlertEnabled() == null || config.getAlertEnabled() != 1) {
            return;
        }

        int availableCount = kamiItemMapper.countUnused(kamiConfigId);
        int totalCount = config.getTotalCount() != null ? config.getTotalCount() : 0;
        int thresholdValue = config.getAlertThresholdValue() != null ? config.getAlertThresholdValue() : 10;
        int thresholdType = config.getAlertThresholdType() != null ? config.getAlertThresholdType() : 1;

        boolean shouldAlert = false;
        if (thresholdType == 1) {
            shouldAlert = availableCount < thresholdValue;
        } else {
            if (totalCount > 0) {
                int percentage = (availableCount * 100) / totalCount;
                shouldAlert = percentage < thresholdValue;
            }
        }

        if (shouldAlert) {
            log.info("卡密库存触发预警: configId={}, available={}, total={}, thresholdType={}, thresholdValue={}",
                    kamiConfigId, availableCount, totalCount, thresholdType, thresholdValue);
            notificationCenterService.dispatch("KAMI_STOCK_LOW", config.getXianyuAccountId(),
                    "卡密库存预警", (config.getAliasName() == null ? "卡密仓库" : config.getAliasName())
                            + " 可用库存剩余 " + availableCount,
                    Map.of("configId", kamiConfigId,
                            "availableCount", availableCount,
                            "totalCount", totalCount));
            emailNotifyService.sendKamiAlertEmail(
                    config.getAlertEmail(),
                    config.getAliasName(),
                    availableCount,
                    totalCount
            );
        }
    }

    private void validateSourceConfig(KamiConfigReqDTO request) {
        String sourceType = request.getSourceType() == null
                ? "LOCAL" : request.getSourceType().trim().toUpperCase();
        if (!List.of("LOCAL", "API").contains(sourceType)) {
            throw new IllegalArgumentException("卡密来源类型无效");
        }
        if (!"API".equals(sourceType)) {
            return;
        }
        WebhookSecurity.requireSafeUrl(request.getExternalApiUrl());
        if (request.getExternalApiBody() == null || request.getExternalApiBody().isBlank()) {
            throw new IllegalArgumentException("请填写外部接口请求体模板");
        }
        if (request.getExternalApiResultPath() == null || request.getExternalApiResultPath().isBlank()) {
            throw new IllegalArgumentException("请填写外部接口卡密结果路径");
        }
        int timeout = request.getExternalApiTimeoutSeconds() == null
                ? 10 : request.getExternalApiTimeoutSeconds();
        if (timeout < 3 || timeout > 30) {
            throw new IllegalArgumentException("外部接口超时时间必须在3到30秒之间");
        }
        try {
            JsonNode body = objectMapper.readTree(request.getExternalApiBody());
            if (!body.isObject()) {
                throw new IllegalArgumentException("外部接口请求体必须是 JSON 对象");
            }
            if (request.getExternalApiHeaders() != null && !request.getExternalApiHeaders().isBlank()
                    && !objectMapper.readTree(request.getExternalApiHeaders()).isObject()) {
                throw new IllegalArgumentException("外部接口请求头必须是 JSON 对象");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("外部接口请求配置不是有效 JSON");
        }
    }

    private boolean hasUnsettledSupply(Long configId) {
        return kamiItemMapper.countUnsettledByConfigId(configId) > 0
                || kamiExternalRequestMapper.countUnsettledByConfigId(configId) > 0;
    }

    private boolean supplyConfigurationChanged(XianyuKamiConfig config, KamiConfigReqDTO request) {
        String requestedSource = request.getSourceType() == null
                ? "LOCAL" : request.getSourceType().trim().toUpperCase();
        if (!requestedSource.equalsIgnoreCase(config.getSourceType())) {
            return true;
        }
        if (!"API".equals(requestedSource)) {
            return false;
        }
        boolean headersChanged = request.getExternalApiHeaders() != null
                && !request.getExternalApiHeaders().isBlank()
                && !Objects.equals(request.getExternalApiHeaders(), config.getExternalApiHeaders());
        return headersChanged
                || !Objects.equals(trimToNull(request.getExternalApiUrl()), trimToNull(config.getExternalApiUrl()))
                || !Objects.equals(request.getExternalApiBody(), config.getExternalApiBody())
                || !Objects.equals(trimToNull(request.getExternalApiResultPath()),
                        trimToNull(config.getExternalApiResultPath()))
                || !Objects.equals(request.getExternalApiTimeoutSeconds() == null
                        ? 10 : request.getExternalApiTimeoutSeconds(), config.getExternalApiTimeoutSeconds());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
