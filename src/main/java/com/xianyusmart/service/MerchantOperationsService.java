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
import com.xianyusmart.entity.XianyuKamiConfig;
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
import java.util.LinkedHashMap;
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
            "PUBLISH_RULE", "DELETE_RULE", "ANNOUNCEMENT", "FEEDBACK", "RISK_EVENT", "WORKFLOW"
    );
    public static final Set<String> TASK_TYPES = Set.of(
            "COLLECT", "SELECT", "PUBLISH", "DELETE", "COMPENSATE", "REFRESH_PROMOTION", "WORKFLOW"
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
    private final OpportunityAnalysisService opportunityAnalysisService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final OperationLogService operationLogService;
    private final AIService aiService;
    private final OpportunityImageService opportunityImageService;
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
                                     OpportunityAnalysisService opportunityAnalysisService,
                                     WorkflowDefinitionService workflowDefinitionService,
                                     OperationLogService operationLogService,
                                     AIService aiService,
                                     OpportunityImageService opportunityImageService,
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
        this.opportunityAnalysisService = opportunityAnalysisService;
        this.workflowDefinitionService = workflowDefinitionService;
        this.operationLogService = operationLogService;
        this.aiService = aiService;
        this.opportunityImageService = opportunityImageService;
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
        if (request.getName().trim().length() > 512) {
            throw new IllegalArgumentException("资源名称不能超过512个字符");
        }
        validateOwnedAccount(request.getXianyuAccountId());
        if ("WORKFLOW".equals(request.getResourceType())) {
            workflowDefinitionService.validateAndSort(request.getData());
        }

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

    public Map<String, Object> searchOpportunities(Map<String, Object> request) {
        String keyword = text(request.get("keyword"));
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("请输入商机关键词");
        }
        Long accountId = longValue(request.get("xianyuAccountId"));
        validateOwnedAccount(accountId);
        int limit = Math.max(1, Math.min(intValue(request.get("limit"), 20), 50));
        int pageNumber = Math.max(1, intValue(request.get("pageNumber"), 1));
        PlatformPublishService.PlatformSearchResult page = platformPublishService.search(
                keyword, accountId, pageNumber, limit);
        return Map.of(
                "items", opportunityAnalysisService.rank(keyword, page.items()),
                "pageNumber", page.pageNumber(),
                "pageSize", page.pageSize(),
                "hasMore", page.hasMore(),
                "total", page.total()
        );
    }

    public Map<String, Object> getSellerPublicProfile(Map<String, Object> request) {
        Long accountId = longValue(request.get("xianyuAccountId"));
        validateOwnedAccount(accountId);
        String itemId = text(request.get("itemId"));
        if (!itemId.matches("\\d{8,}")) {
            throw new IllegalArgumentException("商品ID格式无效");
        }
        Map<String, Object> detail = platformPublishService.collect(
                "https://www.goofish.com/item?id=" + itemId, accountId);
        Map<String, Object> profile = new LinkedHashMap<>();
        for (String key : List.of(
                "sellerId", "sellerNick", "sellerAvatar", "sellerProfileUrl", "sellerCredit",
                "sellerPositiveCount", "sellerNeutralCount", "sellerNegativeCount")) {
            if (detail.containsKey(key)) {
                profile.put(key, detail.get(key));
            }
        }
        profile.put("itemId", itemId);
        return profile;
    }

    public Map<String, Object> crawlShopOpportunities(Map<String, Object> request) {
        String shopUrl = text(request.get("shopUrl"));
        if (shopUrl.isBlank()) {
            throw new IllegalArgumentException("请输入闲鱼店铺链接");
        }
        Long accountId = longValue(request.get("xianyuAccountId"));
        validateOwnedAccount(accountId);
        int limit = Math.max(1, Math.min(intValue(request.get("limit"), 20), 50));
        int pageNumber = Math.max(1, intValue(request.get("pageNumber"), 1));
        PlatformPublishService.PlatformSearchResult page = platformPublishService.crawlShop(
                shopUrl, accountId, pageNumber, limit);
        List<Map<String, Object>> items = page.items().stream().map(candidate -> {
            Map<String, Object> item = new LinkedHashMap<>(candidate);
            item.put("opportunityScore", 75);
            item.put("riskLevel", "LOW");
            item.put("matchReason", "店铺在售商品 · 可采集详情");
            return item;
        }).toList();
        return Map.of(
                "items", items,
                "pageNumber", page.pageNumber(),
                "pageSize", page.pageSize(),
                "hasMore", page.hasMore(),
                "total", page.total()
        );
    }

    public Map<String, Object> polishOpportunity(Map<String, Object> request) {
        String title = text(request.get("title"));
        String description = text(request.get("description"));
        if (title.isBlank()) {
            throw new IllegalArgumentException("商品标题不能为空");
        }
        String prompt = """
                作为闲鱼商品运营助手，在不虚构品牌、成色、配置、售后和价格的前提下改写商品文案。
                只返回JSON，格式为 {"title":"不超过60字","description":"清晰分段的商品详情"}。
                原标题：%s
                原详情：%s
                """.formatted(title, description.isBlank() ? title : description);
        String content = aiService.simpleChat(prompt);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI服务未配置或暂时不可用");
        }
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            Map<String, Object> polished = objectMapper.readValue(
                    start >= 0 && end > start ? content.substring(start, end + 1) : content,
                    new TypeReference<>() { });
            return Map.of(
                    "title", text(polished.get("title")).isBlank() ? title : text(polished.get("title")),
                    "description", text(polished.get("description")).isBlank()
                            ? (description.isBlank() ? title : description) : text(polished.get("description"))
            );
        } catch (Exception e) {
            throw new IllegalStateException("AI返回内容无法解析，请重试");
        }
    }

    public Map<String, Object> generateOpportunityImage(Map<String, Object> request) {
        Long accountId = longValue(request.get("xianyuAccountId"));
        if (accountId == null) {
            throw new IllegalArgumentException("请选择图片上传账号");
        }
        validateOwnedAccount(accountId);
        String prompt = text(request.get("prompt"));
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("请输入商品图生成要求");
        }
        return Map.of("url", opportunityImageService.generate(accountId, prompt));
    }

    @Transactional
    public List<MerchantResourceRespDTO> importOpportunities(Map<String, Object> request) {
        Long accountId = longValue(request.get("xianyuAccountId"));
        validateOwnedAccount(accountId);
        if (!(request.get("candidates") instanceof List<?> candidates) || candidates.isEmpty()) {
            throw new IllegalArgumentException("请选择需要加入货源库的商品");
        }
        if (candidates.size() > 50) {
            throw new IllegalArgumentException("单次最多导入 50 个候选商品");
        }
        Long tenantId = requireTenantId();
        List<MerchantResourceRespDTO> imported = new ArrayList<>();
        for (Object value : candidates) {
            if (!(value instanceof Map<?, ?> candidateValue)) {
                continue;
            }
            Map<String, Object> candidate = normalizeMap(candidateValue);
            String sourceUrl = text(candidate.get("sourceUrl"));
            if (!sourceUrl.isBlank()) {
                // 入库前通过签名详情接口补齐描述和图片，保证后续润色、发布使用完整商品数据。
                try {
                    candidate.putAll(platformPublishService.collect(sourceUrl, accountId));
                } catch (IllegalStateException e) {
                    // 详情接口受限时保留搜索快照，避免真实候选商品在整理环节被直接丢弃。
                    candidate.put("detailStatus", "SEARCH_FALLBACK");
                    candidate.put("detailMessage", e.getMessage());
                    log.warn("商品详情补齐失败，已使用搜索快照继续导入: itemId={}, error={}",
                            candidate.get("itemId"), e.getMessage());
                }
            }
            String itemId = text(candidate.get("itemId"));
            MerchantResource existing = itemId.isBlank() ? null
                    : resourceMapper.selectByTenantTypeAndGoodsId(tenantId, "SUPPLY", itemId);
            MerchantResource supply;
            if (existing == null) {
                supply = createSupply(candidate, accountId);
            } else {
                existing.setName(limitName(text(candidate.get("title"))));
                existing.setDataJson(writeJson(candidate));
                resourceMapper.updateById(existing);
                supply = existing;
            }
            imported.add(toResponse(supply));
        }
        return imported;
    }

    public Map<String, Object> createPublishPlan(Map<String, Object> request) {
        Long accountId = longValue(request.get("xianyuAccountId"));
        validateOwnedAccount(accountId);
        String name = text(request.get("name"));
        String description = text(request.get("description"));
        if (accountId == null || name.isBlank() || description.isBlank()) {
            throw new IllegalArgumentException("发布账号、标题和商品详情不能为空");
        }
        if (!(request.get("images") instanceof List<?> images) || images.isEmpty()) {
            throw new IllegalArgumentException("至少需要一张 HTTPS 商品图片");
        }
        for (Object image : images) {
            if (!(image instanceof String imageUrl) || !imageUrl.startsWith("https://")) {
                throw new IllegalArgumentException("商品图片必须使用 HTTPS 地址");
            }
        }
        BigDecimal amount = decimalValue(request.get("amount"), BigDecimal.ZERO);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("商品价格必须大于 0");
        }
        String requestKey = text(request.get("requestId"));
        MerchantTask existingTask = requestKey.isBlank() ? null
                : taskMapper.selectByRequestKey(requireTenantId(), "PUBLISH", requestKey);
        if (existingTask != null) {
            return existingPublishResult(existingTask);
        }

        Map<String, Object> data = new HashMap<>(request);
        data.remove("dryRun");
        boolean dryRun = Boolean.TRUE.equals(request.get("dryRun"));
        Map<String, Object> preflight = platformPublishService.preflight(request, accountId);
        if (dryRun) {
            // 预检必须经过平台类目和默认地址接口，避免仅校验本地字段造成虚假通过。
            return Map.of("valid", true, "dryRun", true, "preview", data, "platform", preflight);
        }
        MerchantResourceReqDTO materialRequest = new MerchantResourceReqDTO();
        materialRequest.setResourceType("MATERIAL");
        materialRequest.setName(name);
        materialRequest.setStatus(1);
        materialRequest.setXianyuAccountId(accountId);
        materialRequest.setStock(Math.max(0, intValue(request.get("stock"), 1)));
        materialRequest.setAmount(amount);
        materialRequest.setData(data);
        MerchantResourceRespDTO material = saveResource(materialRequest);

        MerchantTaskReqDTO taskRequest = new MerchantTaskReqDTO();
        taskRequest.setTaskType("PUBLISH");
        taskRequest.setRequestKey(requestKey.isBlank() ? null : requestKey);
        taskRequest.setResourceId(material.getId());
        taskRequest.setXianyuAccountId(accountId);
        MerchantTask task = createTask(taskRequest);
        claimAndExecute(task);
        MerchantTask completedTask = taskMapper.selectById(task.getId());
        if (completedTask == null || completedTask.getStatus() != 2) {
            String error = completedTask == null ? "发布任务状态丢失" : completedTask.getErrorMessage();
            return Map.of(
                    "valid", false,
                    "dryRun", false,
                    "material", material,
                    "task", completedTask == null ? task : completedTask,
                    "error", error == null || error.isBlank() ? "商品发布失败" : error
            );
        }
        return Map.of(
                "valid", true,
                "dryRun", false,
                "material", material,
                "task", completedTask,
                "platform", readJson(completedTask.getResultJson())
        );
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
        task.setRequestKey(blankToNull(request.getRequestKey()));
        task.setResourceId(request.getResourceId());
        task.setXianyuAccountId(request.getXianyuAccountId());
        task.setXyGoodsId(blankToNull(request.getXyGoodsId()));
        task.setStatus(0);
        task.setScheduledTime(request.getScheduledTime() == null ? LocalDateTime.now() : request.getScheduledTime());
        task.setAttemptCount(0);
        // 发布结果不确定时禁止自动重发，避免平台已成功但本地超时造成重复商品。
        task.setMaxAttempts("PUBLISH".equals(request.getTaskType()) ? 1 : 3);
        task.setRequestJson(writeJson(request.getRequest()));
        taskMapper.insert(task);
        return task;
    }

    private Map<String, Object> existingPublishResult(MerchantTask task) {
        if (task.getStatus() != null && task.getStatus() == 2) {
            MerchantResource material = requireResource(task.getResourceId());
            return Map.of(
                    "valid", true,
                    "dryRun", false,
                    "material", toResponse(material),
                    "task", task,
                    "platform", readJson(task.getResultJson())
            );
        }
        return Map.of(
                "valid", false,
                "dryRun", false,
                "task", task,
                "error", task.getStatus() != null && task.getStatus() == 1
                        ? "商品正在发布，请勿重复提交"
                        : "同一发布请求已失败，请在任务中心确认远端结果后手动处理"
        );
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
        claimAndExecute(task);
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
        claimAndExecute(task);
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
            task.setMaxAttempts("PUBLISH".equals(task.getTaskType()) ? 1 : 3);
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

    private void claimAndExecute(MerchantTask task) {
        if (taskMapper.claim(task.getId()) != 1) {
            throw new IllegalStateException("任务已被其他执行器处理");
        }
        executeTask(task);
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
                case "WORKFLOW" -> executeWorkflow(task);
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
                supply.setName(limitName(text(candidate.get("title"))));
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

    private Map<String, Object> executeWorkflow(MerchantTask task) {
        MerchantResource workflow = requireResource(task.getResourceId());
        if (!"WORKFLOW".equals(workflow.getResourceType())) {
            throw new IllegalArgumentException("工作流任务未关联有效定义");
        }
        List<Map<String, Object>> nodes = workflowDefinitionService.validateAndSort(readJson(workflow.getDataJson()));
        Long accountId = task.getXianyuAccountId() == null ? workflow.getXianyuAccountId() : task.getXianyuAccountId();
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Long> supplyIds = new ArrayList<>();
        List<Long> materialIds = new ArrayList<>();
        List<Map<String, Object>> nodeResults = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String type = text(node.get("type")).toUpperCase();
            Map<String, Object> config = node.get("config") instanceof Map<?, ?> map
                    ? normalizeMap(map) : Map.of();
            Map<String, Object> result = new HashMap<>();
            result.put("nodeId", text(node.get("id")));
            result.put("type", type);
            switch (type) {
                case "TRIGGER" -> result.put("status", "READY");
                case "SEARCH" -> {
                    String keyword = text(config.get("keyword"));
                    int limit = Math.max(1, Math.min(intValue(config.get("limit"), 20), 50));
                    candidates = opportunityAnalysisService.rank(keyword,
                            platformPublishService.search(keyword, accountId, limit));
                    result.put("count", candidates.size());
                }
                case "FILTER" -> {
                    int minScore = Math.max(0, Math.min(intValue(config.get("minScore"), 60), 100));
                    int limit = Math.max(1, Math.min(intValue(config.get("limit"), 20), 50));
                    List<Map<String, Object>> currentCandidates = candidates;
                    candidates = currentCandidates.stream()
                            .filter(item -> intValue(item.get("opportunityScore"), 0) >= minScore)
                            .limit(limit)
                            .toList();
                    result.put("count", candidates.size());
                }
                case "COLLECT" -> {
                    for (Map<String, Object> candidate : candidates) {
                        String itemId = text(candidate.get("itemId"));
                        MerchantResource supply = itemId.isBlank() ? null
                                : resourceMapper.selectByTenantTypeAndGoodsId(task.getTenantId(), "SUPPLY", itemId);
                        if (supply == null) {
                            supply = createSupply(candidate, accountId);
                        }
                        supplyIds.add(supply.getId());
                    }
                    result.put("count", supplyIds.size());
                }
                case "MATERIAL" -> {
                    for (Long supplyId : supplyIds) {
                        MerchantResource supply = requireResource(supplyId);
                        MerchantResource material = createMaterialFromSupply(supply);
                        ensureDistribution(supply, material);
                        materialIds.add(material.getId());
                    }
                    result.put("count", materialIds.size());
                }
                case "PUBLISH" -> {
                    boolean dryRun = !Boolean.FALSE.equals(config.get("dryRun"));
                    if (!dryRun) {
                        for (Long materialId : materialIds) {
                            MerchantTaskReqDTO publishRequest = new MerchantTaskReqDTO();
                            publishRequest.setTaskType("PUBLISH");
                            publishRequest.setResourceId(materialId);
                            publishRequest.setXianyuAccountId(accountId);
                            MerchantTask publishTask = createTask(publishRequest);
                            claimAndExecute(publishTask);
                            MerchantTask completedTask = taskMapper.selectById(publishTask.getId());
                            if (completedTask == null || completedTask.getStatus() != 2) {
                                throw new IllegalStateException(completedTask == null
                                        ? "发布任务状态丢失" : completedTask.getErrorMessage());
                            }
                        }
                    }
                    result.put("count", materialIds.size());
                    result.put("dryRun", dryRun);
                }
                default -> throw new IllegalArgumentException("不支持的工作流节点");
            }
            nodeResults.add(result);
        }
        return Map.of(
                "candidateCount", candidates.size(),
                "supplyCount", supplyIds.size(),
                "materialCount", materialIds.size(),
                "nodes", nodeResults
        );
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
            supply.setName(limitName(text(collected.get("title"))));
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
        supply.setName(limitName(item.getTitle() == null ? "" : item.getTitle().trim()));
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
                XianyuKamiConfig kamiConfig = kamiConfigMapper.selectById(kamiConfigId);
                if (kamiConfig == null) {
                    throw new IllegalArgumentException("卡券仓库不存在或无权访问");
                }
                if (resource.getXianyuAccountId() == null || resource.getXyGoodsId() == null) {
                    throw new IllegalArgumentException("卡券补偿需先完成商品发布和账号关联");
                }
                if (!resource.getXianyuAccountId().equals(kamiConfig.getXianyuAccountId())) {
                    throw new IllegalArgumentException("卡券仓库与商品账号不一致");
                }
                XianyuGoodsAutoDeliveryConfig deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                        resource.getXianyuAccountId(), resource.getXyGoodsId());
                if (deliveryConfig == null) {
                    deliveryConfig = new XianyuGoodsAutoDeliveryConfig();
                    deliveryConfig.setXianyuAccountId(resource.getXianyuAccountId());
                    deliveryConfig.setXyGoodsId(resource.getXyGoodsId());
                    deliveryConfig.setDeliveryMode(2);
                    deliveryConfig.setFixedTemplateId(null);
                    deliveryConfig.setAutoDeliveryContent(null);
                    deliveryConfig.setKamiConfigIds(String.valueOf(kamiConfigId));
                    deliveryConfig.setKamiDeliveryTemplate(null);
                    deliveryConfig.setDeliveryMessageTemplate(BuyerMessageService.DEFAULT_DELIVERY_MESSAGE_TEMPLATE);
                    deliveryConfig.setVoucherDeliveryEnabled(1);
                    deliveryConfig.setChatDeliveryEnabled(1);
                    deliveryConfig.setAutoConfirmShipment(0);
                    deliveryConfig.setRagDelaySeconds(10);
                    autoDeliveryConfigMapper.insert(deliveryConfig);
                } else {
                    deliveryConfig.setDeliveryMode(2);
                    deliveryConfig.setFixedTemplateId(null);
                    deliveryConfig.setAutoDeliveryContent(null);
                    deliveryConfig.setKamiConfigIds(String.valueOf(kamiConfigId));
                    deliveryConfig.setKamiDeliveryTemplate(null);
                    if (deliveryConfig.getDeliveryMessageTemplate() == null
                            || deliveryConfig.getDeliveryMessageTemplate().isBlank()) {
                        deliveryConfig.setDeliveryMessageTemplate(
                                BuyerMessageService.DEFAULT_DELIVERY_MESSAGE_TEMPLATE);
                    }
                    if (!Integer.valueOf(1).equals(deliveryConfig.getVoucherDeliveryEnabled())
                            && !Integer.valueOf(1).equals(deliveryConfig.getChatDeliveryEnabled())) {
                        deliveryConfig.setVoucherDeliveryEnabled(1);
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

    private MerchantResource createSupply(Map<String, Object> candidate, Long accountId) {
        MerchantResource supply = new MerchantResource();
        supply.setTenantId(requireTenantId());
        supply.setResourceType("SUPPLY");
        supply.setName(limitName(text(candidate.get("title"))).isBlank() ? "待完善货源" : limitName(text(candidate.get("title"))));
        supply.setStatus(1);
        supply.setXianyuAccountId(accountId);
        supply.setXyGoodsId(blankToNull(text(candidate.get("itemId"))));
        supply.setStock(Math.max(0, intValue(candidate.get("stock"), 1)));
        Object price = candidate.get("price") == null ? candidate.get("amount") : candidate.get("price");
        supply.setAmount(decimalValue(price, BigDecimal.ZERO));
        supply.setDataJson(writeJson(candidate));
        resourceMapper.insert(supply);
        return supply;
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
            case "WORKFLOW" -> "WORKFLOW";
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

    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
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

    /**
     * merchant_resource.name 上限 512 字符，外部平台返回的商品标题可能超长，入库前统一截断。
     */
    private String limitName(String value) {
        return value.length() > 512 ? value.substring(0, 512) : value;
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
