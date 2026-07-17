package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.ItemDetailReqDTO;
import com.xianyusmart.controller.dto.ItemDetailRespDTO;
import com.xianyusmart.controller.dto.ItemWithConfigDTO;
import com.xianyusmart.controller.dto.MerchantDistributionReqDTO;
import com.xianyusmart.controller.dto.MerchantResourceReqDTO;
import com.xianyusmart.controller.dto.MerchantResourceRespDTO;
import com.xianyusmart.controller.dto.MerchantTaskReqDTO;
import com.xianyusmart.entity.MerchantDistribution;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.entity.MerchantShortLink;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.mapper.MerchantDistributionMapper;
import com.xianyusmart.mapper.MerchantResourceMapper;
import com.xianyusmart.mapper.MerchantTaskMapper;
import com.xianyusmart.mapper.MerchantShortLinkMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 商家运营资源、任务和分销服务
 */
@Slf4j
@Service
public class MerchantOperationsService {

    public static final Set<String> RESOURCE_TYPES = Set.of(
            "ADDRESS", "MATERIAL", "SUPPLY", "PROMOTION_ACCOUNT", "SELECTION_RULE",
            "PUBLISH_RULE", "DELETE_RULE", "ANNOUNCEMENT", "FEEDBACK", "RISK_EVENT"
    );
    public static final Set<String> TASK_TYPES = Set.of(
            "COLLECT", "SELECT", "PUBLISH", "DELETE", "COMPENSATE", "REFRESH_PROMOTION"
    );

    private final MerchantResourceMapper resourceMapper;
    private final MerchantTaskMapper taskMapper;
    private final MerchantDistributionMapper distributionMapper;
    private final XianyuAccountMapper accountMapper;
    private final XianyuKamiConfigMapper kamiConfigMapper;
    private final MerchantShortLinkMapper shortLinkMapper;
    private final XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    private final ItemService itemService;
    private final PlatformPublishService platformPublishService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public MerchantOperationsService(MerchantResourceMapper resourceMapper,
                                     MerchantTaskMapper taskMapper,
                                     MerchantDistributionMapper distributionMapper,
                                     XianyuAccountMapper accountMapper,
                                     XianyuKamiConfigMapper kamiConfigMapper,
                                     MerchantShortLinkMapper shortLinkMapper,
                                     XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper,
                                     ItemService itemService,
                                     PlatformPublishService platformPublishService,
                                     OperationLogService operationLogService,
                                     ObjectMapper objectMapper) {
        this.resourceMapper = resourceMapper;
        this.taskMapper = taskMapper;
        this.distributionMapper = distributionMapper;
        this.accountMapper = accountMapper;
        this.kamiConfigMapper = kamiConfigMapper;
        this.shortLinkMapper = shortLinkMapper;
        this.autoDeliveryConfigMapper = autoDeliveryConfigMapper;
        this.itemService = itemService;
        this.platformPublishService = platformPublishService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    public List<MerchantResourceRespDTO> listResources(String type, Integer status) {
        requireResourceType(type);
        return resourceMapper.selectByType(type, status).stream().map(this::toResponse).toList();
    }

    public Map<String, Object> getOverview() {
        Map<String, Long> resourceCounts = new HashMap<>();
        RESOURCE_TYPES.forEach(type -> resourceCounts.put(type, 0L));
        for (Map<String, Object> row : resourceMapper.selectTypeCounts()) {
            String resourceType = text(row.get("resourceType"));
            if (RESOURCE_TYPES.contains(resourceType)) {
                resourceCounts.put(resourceType, countValue(row.get("resourceCount")));
            }
        }

        Map<String, Object> taskCounts = taskMapper.selectOverviewCounts();
        Map<String, Object> overview = new HashMap<>();
        overview.put("resourceCounts", resourceCounts);
        overview.put("taskCount", countValue(taskCounts == null ? null : taskCounts.get("taskCount")));
        overview.put("failedTaskCount", countValue(taskCounts == null ? null : taskCounts.get("failedTaskCount")));
        return overview;
    }

    @Transactional
    public MerchantResourceRespDTO saveResource(MerchantResourceReqDTO request) {
        requireResourceType(request.getResourceType());
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("资源名称不能为空");
        }
        if (request.getName().trim().length() > 200) {
            throw new IllegalArgumentException("资源名称不能超过200个字符");
        }
        validateOwnedAccount(request.getXianyuAccountId());

        MerchantResource resource = request.getId() == null ? new MerchantResource() : resourceMapper.selectById(request.getId());
        if (resource == null) {
            throw new IllegalArgumentException("运营资源不存在");
        }
        if (request.getId() == null) {
            resource.setTenantId(requireTenantId());
        }
        resource.setResourceType(request.getResourceType());
        resource.setName(request.getName().trim());
        resource.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        resource.setXianyuAccountId(request.getXianyuAccountId());
        resource.setXyGoodsId(blankToNull(request.getXyGoodsId()));
        resource.setStock(request.getStock() == null ? 0 : Math.max(0, request.getStock()));
        resource.setAmount(request.getAmount() == null ? BigDecimal.ZERO : request.getAmount().max(BigDecimal.ZERO));
        resource.setScheduledTime(request.getScheduledTime());
        String dataJson = writeJson(request.getData());
        if (dataJson != null && dataJson.length() > 1024 * 1024) {
            throw new IllegalArgumentException("资源扩展数据不能超过1MB");
        }
        resource.setDataJson(dataJson);
        if (request.getId() == null) {
            resourceMapper.insert(resource);
        } else {
            resourceMapper.updateById(resource);
        }
        return toResponse(resourceMapper.selectById(resource.getId()));
    }

    public void deleteResource(Long id) {
        if (resourceMapper.deleteById(id) == 0) {
            throw new IllegalArgumentException("运营资源不存在");
        }
    }

    public MerchantTask createTask(MerchantTaskReqDTO request) {
        requireTaskType(request.getTaskType());
        validateOwnedAccount(request.getXianyuAccountId());
        MerchantResource resource = request.getResourceId() == null ? null : resourceMapper.selectById(request.getResourceId());
        if (request.getResourceId() != null && resource == null) {
            throw new IllegalArgumentException("运营资源不存在");
        }
        MerchantTask task = new MerchantTask();
        task.setTenantId(requireTenantId());
        task.setTaskType(request.getTaskType());
        task.setResourceId(request.getResourceId());
        task.setXianyuAccountId(request.getXianyuAccountId());
        task.setXyGoodsId(blankToNull(request.getXyGoodsId()));
        task.setStatus(0);
        task.setScheduledTime(request.getScheduledTime() == null ? LocalDateTime.now() : request.getScheduledTime());
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setRequestJson(writeJson(request.getRequest()));
        taskMapper.insert(task);
        return task;
    }

    public List<MerchantTask> batchPublish(Map<String, Object> request) {
        Long accountId = longValue(request.get("xianyuAccountId"));
        validateOwnedAccount(accountId);
        Object resourceIdsValue = request.get("resourceIds");
        if (!(resourceIdsValue instanceof List<?> resourceIds) || resourceIds.isEmpty()) {
            throw new IllegalArgumentException("请选择待发布素材");
        }
        List<MerchantTask> tasks = new ArrayList<>();
        for (Object resourceIdValue : resourceIds) {
            Long resourceId = longValue(resourceIdValue);
            MerchantResource resource = resourceId == null ? null : resourceMapper.selectById(resourceId);
            if (resource == null || !"MATERIAL".equals(resource.getResourceType())) {
                throw new IllegalArgumentException("批量发布包含无效素材");
            }
            MerchantTaskReqDTO taskRequest = new MerchantTaskReqDTO();
            taskRequest.setTaskType("PUBLISH");
            taskRequest.setResourceId(resourceId);
            Long effectiveAccountId = accountId == null ? resource.getXianyuAccountId() : accountId;
            if (effectiveAccountId == null) {
                throw new IllegalArgumentException("素材未关联发布账号");
            }
            taskRequest.setXianyuAccountId(effectiveAccountId);
            taskRequest.setScheduledTime(LocalDateTime.now());
            tasks.add(createTask(taskRequest));
        }
        return tasks;
    }

    @Transactional
    public MerchantTask executeResource(Long resourceId) {
        MerchantResource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new IllegalArgumentException("运营资源不存在");
        }
        MerchantTaskReqDTO request = new MerchantTaskReqDTO();
        request.setTaskType(taskTypeFor(resource.getResourceType()));
        request.setResourceId(resource.getId());
        request.setXianyuAccountId(resource.getXianyuAccountId());
        request.setXyGoodsId(resource.getXyGoodsId());
        request.setScheduledTime(LocalDateTime.now());
        MerchantTask task = createTask(request);
        executeTask(task);
        return taskMapper.selectById(task.getId());
    }

    @Transactional
    public MerchantTask compensateResource(Long resourceId) {
        MerchantResource resource = requireResource(resourceId);
        MerchantTaskReqDTO request = new MerchantTaskReqDTO();
        request.setTaskType("COMPENSATE");
        request.setResourceId(resource.getId());
        request.setXianyuAccountId(resource.getXianyuAccountId());
        request.setScheduledTime(LocalDateTime.now());
        MerchantTask task = createTask(request);
        executeTask(task);
        return taskMapper.selectById(task.getId());
    }

    public List<MerchantTask> listTasks(String taskType, Integer status, Integer limit) {
        if (taskType != null && !taskType.isBlank()) {
            requireTaskType(taskType);
        }
        return taskMapper.selectRecent(taskType, status, normalizeLimit(limit));
    }

    public List<MerchantDistribution> listDistributions(Integer status, Integer settlementStatus, Integer limit) {
        return distributionMapper.selectRecent(status, settlementStatus, normalizeLimit(limit));
    }

    @Transactional
    public MerchantDistribution saveDistribution(MerchantDistributionReqDTO request) {
        MerchantResource supply = resourceMapper.selectById(request.getSupplyResourceId());
        if (supply == null || !"SUPPLY".equals(supply.getResourceType())) {
            throw new IllegalArgumentException("货源不存在");
        }
        if (request.getMaterialResourceId() != null && resourceMapper.selectById(request.getMaterialResourceId()) == null) {
            throw new IllegalArgumentException("素材不存在");
        }
        validateOwnedAccount(request.getXianyuAccountId());
        MerchantDistribution distribution = new MerchantDistribution();
        distribution.setTenantId(requireTenantId());
        distribution.setSupplyResourceId(request.getSupplyResourceId());
        distribution.setMaterialResourceId(request.getMaterialResourceId());
        distribution.setXianyuAccountId(request.getXianyuAccountId());
        distribution.setXyGoodsId(blankToNull(request.getXyGoodsId()));
        distribution.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        distribution.setCommissionAmount(request.getCommissionAmount() == null ? BigDecimal.ZERO : request.getCommissionAmount());
        distribution.setSettlementStatus(0);
        distribution.setDataJson(writeJson(request.getData()));
        distributionMapper.insert(distribution);
        return distribution;
    }

    @Transactional
    public MerchantResourceRespDTO convertSupplyToMaterial(Long supplyId) {
        MerchantResource supply = resourceMapper.selectById(supplyId);
        if (supply == null || !"SUPPLY".equals(supply.getResourceType())) {
            throw new IllegalArgumentException("货源不存在");
        }
        MerchantResource material = createMaterialFromSupply(supply);
        ensureDistribution(supply, material);
        return toResponse(material);
    }

    public void settleDistribution(Long id) {
        if (distributionMapper.settle(id) == 0) {
            throw new IllegalArgumentException("分销记录不存在或已结算");
        }
    }

    public void requeueTask(Long id) {
        if (taskMapper.requeue(id) == 0) {
            throw new IllegalArgumentException("任务不存在");
        }
    }

    @Transactional
    public void scheduleDueRules() {
        for (MerchantResource rule : resourceMapper.selectDueRules(50)) {
            MerchantTask task = new MerchantTask();
            task.setTenantId(rule.getTenantId());
            task.setTaskType(taskTypeFor(rule.getResourceType()));
            task.setResourceId(rule.getId());
            task.setXianyuAccountId(rule.getXianyuAccountId());
            task.setXyGoodsId(rule.getXyGoodsId());
            task.setStatus(0);
            task.setScheduledTime(LocalDateTime.now());
            task.setAttemptCount(0);
            task.setMaxAttempts(3);
            task.setRequestJson(rule.getDataJson());
            taskMapper.insert(task);
            int intervalMinutes = Math.max(5, intValue(readJson(rule.getDataJson()).get("intervalMinutes"), 1440));
            resourceMapper.updateNextRun(rule.getId(), LocalDateTime.now().plusMinutes(intervalMinutes));
        }
    }

    public void processDueTasks() {
        for (MerchantTask task : taskMapper.selectDue(20)) {
            if (taskMapper.claim(task.getId()) == 1) {
                executeTask(task);
            }
        }
    }

    void executeTask(MerchantTask task) {
        TenantContext.set(task.getTenantId());
        try {
            Map<String, Object> result = switch (task.getTaskType()) {
                case "SELECT" -> executeSelection(task);
                case "PUBLISH" -> executePublish(task);
                case "DELETE" -> executeDelete(task);
                case "COLLECT" -> executeCollect(task);
                case "COMPENSATE" -> executeCompensation(task);
                case "REFRESH_PROMOTION" -> executePromotionRefresh(task);
                default -> throw new IllegalArgumentException("不支持的任务类型");
            };
            taskMapper.complete(task.getId(), writeJson(result));
            operationLogService.log(task.getXianyuAccountId(), OperationConstants.Type.UPDATE,
                    OperationConstants.Module.MERCHANT_OPERATIONS, task.getTaskType() + "任务执行成功",
                    OperationConstants.Status.SUCCESS, OperationConstants.TargetType.TASK,
                    String.valueOf(task.getId()), task.getRequestJson(), writeJson(result), null, null);
        } catch (Exception e) {
            int attempt = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
            taskMapper.fail(task.getId(), trimError(e.getMessage()), LocalDateTime.now().plusMinutes(Math.min(60, attempt * 5L)));
            operationLogService.log(task.getXianyuAccountId(), OperationConstants.Type.UPDATE,
                    OperationConstants.Module.MERCHANT_OPERATIONS, task.getTaskType() + "任务执行失败",
                    OperationConstants.Status.FAIL, OperationConstants.TargetType.TASK,
                    String.valueOf(task.getId()), task.getRequestJson(), null, trimError(e.getMessage()), null);
            log.warn("运营任务执行失败: taskId={}, type={}, error={}", task.getId(), task.getTaskType(), e.getMessage());
            recordRiskEvent(task, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private Map<String, Object> executeSelection(MerchantTask task) {
        MerchantResource rule = requireResource(task.getResourceId());
        Map<String, Object> config = readJson(rule.getDataJson());
        String keyword = text(config.get("keyword"));
        BigDecimal minAmount = decimalValue(config.get("minAmount"), BigDecimal.ZERO);
        BigDecimal maxAmount = decimalValue(config.get("maxAmount"), new BigDecimal("99999999"));
        int minStock = intValue(config.get("minStock"), 0);
        int collected = 0;
        if (!keyword.isBlank()) {
            int searchLimit = Math.max(1, Math.min(intValue(config.get("searchLimit"), 20), 50));
            for (Map<String, Object> candidate : platformPublishService.search(keyword, rule.getXianyuAccountId(), searchLimit)) {
                String itemId = text(candidate.get("itemId"));
                if (itemId.isBlank() || resourceMapper.selectByTenantTypeAndGoodsId(task.getTenantId(), "SUPPLY", itemId) != null) {
                    continue;
                }
                MerchantResource supply = new MerchantResource();
                supply.setTenantId(task.getTenantId());
                supply.setResourceType("SUPPLY");
                supply.setName(text(candidate.get("title")));
                supply.setStatus(1);
                supply.setXianyuAccountId(rule.getXianyuAccountId());
                supply.setXyGoodsId(itemId);
                supply.setStock(1);
                supply.setAmount(decimalValue(candidate.get("amount"), BigDecimal.ZERO));
                supply.setDataJson(writeJson(candidate));
                resourceMapper.insert(supply);
                collected++;
            }
        }
        int created = 0;
        for (MerchantResource supply : resourceMapper.selectEnabledByTenantAndType(task.getTenantId(), "SUPPLY")) {
            if ((!keyword.isBlank() && !supply.getName().contains(keyword))
                    || supply.getAmount().compareTo(minAmount) < 0 || supply.getAmount().compareTo(maxAmount) > 0
                    || supply.getStock() < minStock) {
                continue;
            }
            MerchantResource material = createMaterialFromSupply(supply);
            ensureDistribution(supply, material);
            created++;
        }
        return Map.of("collected", collected, "selected", created);
    }

    private Map<String, Object> executePublish(MerchantTask task) {
        MerchantResource resource = requireResource(task.getResourceId());
        MerchantResource material = resource;
        Map<String, Object> publishConfig = readJson(resource.getDataJson());
        if ("PUBLISH_RULE".equals(resource.getResourceType())) {
            Long materialId = longValue(publishConfig.get("materialId"));
            material = requireResource(materialId);
        }
        if (!"MATERIAL".equals(material.getResourceType())) {
            throw new IllegalArgumentException("发布任务未关联素材");
        }
        Long accountId = task.getXianyuAccountId() != null ? task.getXianyuAccountId() : material.getXianyuAccountId();
        if (accountId == null) {
            throw new IllegalArgumentException("发布任务未关联账号");
        }
        Map<String, Object> materialData = readJson(material.getDataJson());
        Long addressId = longValue(publishConfig.get("addressId"));
        if (addressId == null) {
            addressId = longValue(materialData.get("addressId"));
        }
        Map<String, Object> address = Map.of();
        if (addressId != null) {
            MerchantResource addressResource = requireResource(addressId);
            if (!"ADDRESS".equals(addressResource.getResourceType())) {
                throw new IllegalArgumentException("发布地址关联无效");
            }
            address = readJson(addressResource.getDataJson());
        }
        Map<String, Object> result = platformPublishService.publish(material, accountId, address);
        String itemId = text(result.get("itemId"));
        if (!itemId.isBlank()) {
            material.setXianyuAccountId(accountId);
            material.setXyGoodsId(itemId);
            material.setStatus(2);
            resourceMapper.updateById(material);
            updateDistributionPublished(material.getId(), accountId, itemId);
        }
        return result;
    }

    private Map<String, Object> executeDelete(MerchantTask task) {
        MerchantResource rule = requireResource(task.getResourceId());
        Long accountId = task.getXianyuAccountId() != null ? task.getXianyuAccountId() : rule.getXianyuAccountId();
        String goodsId = task.getXyGoodsId() != null ? task.getXyGoodsId() : rule.getXyGoodsId();
        if (accountId == null || goodsId == null) {
            throw new IllegalArgumentException("删除任务未关联账号或商品");
        }
        return platformPublishService.delete(accountId, goodsId);
    }

    private Map<String, Object> executeCollect(MerchantTask task) {
        MerchantResource supply = requireResource(task.getResourceId());
        Map<String, Object> data = readJson(supply.getDataJson());
        String sourceUrl = text(data.get("sourceUrl"));
        if (!sourceUrl.isBlank()) {
            Map<String, Object> collected = platformPublishService.collect(sourceUrl, supply.getXianyuAccountId());
            data.putAll(collected);
            supply.setName(text(collected.get("title")));
            String itemId = text(collected.get("itemId"));
            if (!itemId.isBlank()) {
                supply.setXyGoodsId(itemId);
            }
            supply.setDataJson(writeJson(data));
            resourceMapper.updateById(supply);
            return Map.of("itemId", supply.getXyGoodsId() == null ? "" : supply.getXyGoodsId(), "name", supply.getName());
        }
        if (supply.getXianyuAccountId() == null || supply.getXyGoodsId() == null) {
            throw new IllegalArgumentException("采集货源需填写来源地址，或关联账号和商品ID");
        }
        ItemDetailReqDTO request = new ItemDetailReqDTO();
        request.setXyGoodId(supply.getXyGoodsId());
        request.setCookieId(String.valueOf(supply.getXianyuAccountId()));
        ResultObject<ItemDetailRespDTO> result = itemService.getItemDetail(request);
        if (result.getCode() != 200 || result.getData() == null || result.getData().getItemWithConfig() == null) {
            throw new IllegalStateException(result.getMsg());
        }
        ItemWithConfigDTO itemWithConfig = result.getData().getItemWithConfig();
        XianyuGoodsInfo item = itemWithConfig.getItem();
        data.put("title", item.getTitle());
        data.put("description", item.getDetailInfo());
        data.put("images", collectImages(item));
        data.put("detailUrl", item.getDetailUrl());
        supply.setName(item.getTitle());
        supply.setDataJson(writeJson(data));
        if (item.getSoldPrice() != null) {
            supply.setAmount(decimalValue(item.getSoldPrice(), supply.getAmount()));
        }
        resourceMapper.updateById(supply);
        return Map.of("itemId", supply.getXyGoodsId(), "name", supply.getName());
    }

    private Map<String, Object> executeCompensation(MerchantTask task) {
        Long targetTaskId = longValue(readJson(task.getRequestJson()).get("targetTaskId"));
        if (targetTaskId != null && taskMapper.requeue(targetTaskId) == 1) {
            return Map.of("requeuedTaskId", targetTaskId);
        }
        MerchantResource resource = requireResource(task.getResourceId());
        if ("MATERIAL".equals(resource.getResourceType())) {
            Map<String, Object> data = readJson(resource.getDataJson());
            Map<String, Object> repaired = new HashMap<>();
            if (resource.getXyGoodsId() != null) {
                updateDistributionPublished(resource.getId(), resource.getXianyuAccountId(), resource.getXyGoodsId());
                repaired.put("publishedItemId", resource.getXyGoodsId());
            }
            String targetUrl = text(data.get("targetUrl"));
            if (targetUrl.isBlank()) {
                targetUrl = text(data.get("sourceUrl"));
            }
            if (!targetUrl.isBlank() && text(data.get("shortUrl")).isBlank()) {
                String token = createShortLink(resource.getTenantId(), targetUrl);
                data.put("shortUrl", "/s/" + token);
                repaired.put("shortUrl", "/s/" + token);
            }
            Long kamiConfigId = longValue(data.get("kamiConfigId"));
            if (kamiConfigId != null) {
                if (kamiConfigMapper.selectById(kamiConfigId) == null) {
                    throw new IllegalArgumentException("卡券仓库不存在或无权访问");
                }
                if (resource.getXianyuAccountId() == null || resource.getXyGoodsId() == null) {
                    throw new IllegalArgumentException("卡券补偿需先完成商品发布和账号关联");
                }
                XianyuGoodsAutoDeliveryConfig deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                        resource.getXianyuAccountId(), resource.getXyGoodsId());
                if (deliveryConfig == null) {
                    deliveryConfig = new XianyuGoodsAutoDeliveryConfig();
                    deliveryConfig.setXianyuAccountId(resource.getXianyuAccountId());
                    deliveryConfig.setXyGoodsId(resource.getXyGoodsId());
                    deliveryConfig.setDeliveryMode(2);
                    deliveryConfig.setKamiConfigIds(String.valueOf(kamiConfigId));
                    deliveryConfig.setKamiDeliveryTemplate("{kmKey}");
                    deliveryConfig.setAutoConfirmShipment(0);
                    deliveryConfig.setRagDelaySeconds(10);
                    autoDeliveryConfigMapper.insert(deliveryConfig);
                } else {
                    deliveryConfig.setDeliveryMode(2);
                    deliveryConfig.setKamiConfigIds(String.valueOf(kamiConfigId));
                    if (deliveryConfig.getKamiDeliveryTemplate() == null || deliveryConfig.getKamiDeliveryTemplate().isBlank()) {
                        deliveryConfig.setKamiDeliveryTemplate("{kmKey}");
                    }
                    autoDeliveryConfigMapper.updateById(deliveryConfig);
                }
                repaired.put("kamiConfigId", kamiConfigId);
            }
            if (!repaired.isEmpty()) {
                resource.setDataJson(writeJson(data));
                resourceMapper.updateById(resource);
                repaired.put("repairedMaterialId", resource.getId());
                return repaired;
            }
        }
        throw new IllegalArgumentException("补偿任务缺少可修复目标");
    }

    private String createShortLink(Long tenantId, String targetUrl) {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        } while (shortLinkMapper.selectByToken(token) != null);
        MerchantShortLink shortLink = new MerchantShortLink();
        shortLink.setTenantId(tenantId);
        shortLink.setToken(token);
        shortLink.setTargetUrl(targetUrl);
        shortLink.setClickCount(0L);
        shortLinkMapper.insert(shortLink);
        return token;
    }

    private void recordRiskEvent(MerchantTask task, String errorMessage) {
        String error = trimError(errorMessage);
        if (!(error.contains("异常流量") || error.contains("风控") || error.contains("滑块") || error.contains("验证"))) {
            return;
        }
        MerchantResource risk = new MerchantResource();
        risk.setTenantId(task.getTenantId());
        risk.setResourceType("RISK_EVENT");
        risk.setName(task.getTaskType() + "任务触发平台验证");
        risk.setStatus(1);
        risk.setXianyuAccountId(task.getXianyuAccountId());
        risk.setXyGoodsId(task.getXyGoodsId());
        risk.setStock(0);
        risk.setAmount(BigDecimal.ZERO);
        risk.setDataJson(writeJson(Map.of("level", "HIGH", "content", error, "taskId", task.getId())));
        resourceMapper.insert(risk);
    }

    private Map<String, Object> executePromotionRefresh(MerchantTask task) {
        MerchantResource accountResource = requireResource(task.getResourceId());
        if (!"PROMOTION_ACCOUNT".equals(accountResource.getResourceType()) || accountResource.getXianyuAccountId() == null) {
            throw new IllegalArgumentException("返佣账号未关联闲鱼账号");
        }
        XianyuAccount account = accountMapper.selectById(accountResource.getXianyuAccountId());
        if (account == null) {
            throw new IllegalArgumentException("返佣账号不存在");
        }
        accountResource.setStatus(account.getStatus() == null ? 0 : account.getStatus());
        accountResource.setLastRunTime(LocalDateTime.now());
        resourceMapper.updateById(accountResource);
        return Map.of("accountId", account.getId(), "status", accountResource.getStatus());
    }

    private MerchantResource createMaterialFromSupply(MerchantResource supply) {
        MerchantResource existing = resourceMapper.selectByTenantTypeAndName(supply.getTenantId(), "MATERIAL", supply.getName());
        if (existing != null) {
            return existing;
        }
        Map<String, Object> data = readJson(supply.getDataJson());
        data.put("sourceResourceId", supply.getId());
        data.putIfAbsent("title", supply.getName());
        MerchantResource material = new MerchantResource();
        material.setTenantId(supply.getTenantId());
        material.setResourceType("MATERIAL");
        material.setName(supply.getName());
        material.setStatus(1);
        material.setXianyuAccountId(supply.getXianyuAccountId());
        material.setStock(supply.getStock());
        material.setAmount(supply.getAmount());
        material.setDataJson(writeJson(data));
        resourceMapper.insert(material);
        return material;
    }

    private void ensureDistribution(MerchantResource supply, MerchantResource material) {
        if (distributionMapper.selectRelation(supply.getTenantId(), supply.getId(), material.getId()) != null) {
            return;
        }
        MerchantDistribution distribution = new MerchantDistribution();
        distribution.setTenantId(supply.getTenantId());
        distribution.setSupplyResourceId(supply.getId());
        distribution.setMaterialResourceId(material.getId());
        distribution.setXianyuAccountId(material.getXianyuAccountId());
        distribution.setStatus(0);
        distribution.setCommissionAmount(decimalValue(readJson(supply.getDataJson()).get("commissionAmount"), BigDecimal.ZERO));
        distribution.setSettlementStatus(0);
        distributionMapper.insert(distribution);
    }

    private void updateDistributionPublished(Long materialId, Long accountId, String goodsId) {
        MerchantResource material = requireResource(materialId);
        distributionMapper.updatePublishedByMaterial(material.getTenantId(), materialId, accountId, goodsId);
    }

    private MerchantResource requireResource(Long id) {
        MerchantResource resource = id == null ? null : resourceMapper.selectById(id);
        if (resource == null) {
            throw new IllegalArgumentException("运营资源不存在");
        }
        return resource;
    }

    private String taskTypeFor(String resourceType) {
        return switch (resourceType) {
            case "SUPPLY" -> "COLLECT";
            case "SELECTION_RULE" -> "SELECT";
            case "MATERIAL", "PUBLISH_RULE" -> "PUBLISH";
            case "DELETE_RULE" -> "DELETE";
            case "PROMOTION_ACCOUNT" -> "REFRESH_PROMOTION";
            default -> throw new IllegalArgumentException("该资源不支持执行任务");
        };
    }

    private MerchantResourceRespDTO toResponse(MerchantResource resource) {
        MerchantResourceRespDTO response = new MerchantResourceRespDTO();
        response.setId(resource.getId());
        response.setResourceType(resource.getResourceType());
        response.setName(resource.getName());
        response.setStatus(resource.getStatus());
        response.setXianyuAccountId(resource.getXianyuAccountId());
        response.setXyGoodsId(resource.getXyGoodsId());
        response.setStock(resource.getStock());
        response.setAmount(resource.getAmount());
        response.setScheduledTime(resource.getScheduledTime());
        response.setLastRunTime(resource.getLastRunTime());
        response.setData(readJson(resource.getDataJson()));
        response.setCreatedTime(resource.getCreatedTime());
        response.setUpdatedTime(resource.getUpdatedTime());
        return response;
    }

    private List<String> collectImages(XianyuGoodsInfo item) {
        List<String> images = new ArrayList<>();
        if (item.getCoverPic() != null && !item.getCoverPic().isBlank()) {
            images.add(item.getCoverPic());
        }
        if (item.getInfoPic() != null && !item.getInfoPic().isBlank()) {
            try {
                images.addAll(objectMapper.readValue(item.getInfoPic(), new TypeReference<List<String>>() { }));
            } catch (Exception ignored) {
            }
        }
        return images;
    }

    private void validateOwnedAccount(Long accountId) {
        if (accountId != null && accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("账号不存在或无权访问");
        }
    }

    private Long requireTenantId() {
        Long tenantId = UserContext.getUserId();
        if (tenantId == null) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    private void requireResourceType(String type) {
        if (type == null || !RESOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的资源类型");
        }
    }

    private void requireTaskType(String type) {
        if (type == null || !TASK_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的任务类型");
        }
    }

    private int normalizeLimit(Integer limit) {
        return limit == null ? 100 : Math.max(1, Math.min(limit, 500));
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("扩展数据格式错误", e);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("扩展数据序列化失败", e);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long countValue(Object value) {
        Long count = longValue(value);
        return count == null ? 0L : count;
    }

    private BigDecimal decimalValue(Object value, BigDecimal defaultValue) {
        try {
            return value == null ? defaultValue : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String trimError(String error) {
        if (error == null || error.isBlank()) {
            return "执行失败";
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }
}
