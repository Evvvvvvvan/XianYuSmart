package com.xianyusmart.service;

import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.controller.dto.OrderRateDetailDTO;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 商品评价和擦亮自动化服务
 */
@Slf4j
@Service
public class GoodsAutomationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RATE_PER_ACCOUNT = 20;
    private static final int RATE_DETAIL_PAGE_SIZE = 100;
    private static final int MAX_RATE_SCAN_PAGES = 5;
    private static final String TRADE_COMPLETED = "交易成功";
    private static final String RATE_PENDING = "5";
    private static final String RATE_COMPLETED = "4";

    private final XianyuGoodsConfigMapper goodsConfigMapper;
    private final XianyuGoodsOrderMapper goodsOrderMapper;
    private final AccountService accountService;
    private final XianyuApiCallUtils apiCallUtils;
    private final OperationLogService operationLogService;
    private final XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    private final BuyerMessageService buyerMessageService;
    private final RatingContentService ratingContentService;
    private final Map<Long, Integer> rateScanStartPages = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public GoodsAutomationService(XianyuGoodsConfigMapper goodsConfigMapper,
                                  XianyuGoodsOrderMapper goodsOrderMapper,
                                  AccountService accountService,
                                  XianyuApiCallUtils apiCallUtils,
                                  OperationLogService operationLogService,
                                  XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper,
                                  BuyerMessageService buyerMessageService,
                                  RatingContentService ratingContentService) {
        this(goodsConfigMapper, goodsOrderMapper, accountService, apiCallUtils, operationLogService,
                autoDeliveryConfigMapper, buyerMessageService, ratingContentService,
                Clock.system(BUSINESS_ZONE));
    }

    GoodsAutomationService(XianyuGoodsConfigMapper goodsConfigMapper,
                           XianyuGoodsOrderMapper goodsOrderMapper,
                           AccountService accountService,
                           XianyuApiCallUtils apiCallUtils,
                           OperationLogService operationLogService,
                           XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper,
                           BuyerMessageService buyerMessageService,
                           RatingContentService ratingContentService,
                           Clock clock) {
        this.goodsConfigMapper = goodsConfigMapper;
        this.goodsOrderMapper = goodsOrderMapper;
        this.accountService = accountService;
        this.apiCallUtils = apiCallUtils;
        this.operationLogService = operationLogService;
        this.autoDeliveryConfigMapper = autoDeliveryConfigMapper;
        this.buyerMessageService = buyerMessageService;
        this.ratingContentService = ratingContentService;
        this.clock = clock;
    }

    public void runAutoRate() {
        Map<Long, List<XianyuGoodsConfig>> configsByAccount = goodsConfigMapper.selectAutoRateEnabled().stream()
                .filter(config -> config.getXianyuAccountId() != null && config.getXyGoodsId() != null)
                .collect(Collectors.groupingBy(XianyuGoodsConfig::getXianyuAccountId));
        configsByAccount.forEach((accountId, configs) -> {
            try {
                ratePendingOrders(accountId, configs);
            } catch (Exception e) {
                log.warn("【账号{}】自动评价任务执行失败: {}", accountId, e.getMessage());
            }
        });
    }

    public void runAutoPolish() {
        Map<Long, List<XianyuGoodsConfig>> configsByAccount = goodsConfigMapper.selectAutoPolishEnabled().stream()
                .filter(config -> config.getXianyuAccountId() != null && config.getXyGoodsId() != null)
                .collect(Collectors.groupingBy(XianyuGoodsConfig::getXianyuAccountId));
        configsByAccount.values().forEach(configs -> {
            for (XianyuGoodsConfig config : configs) {
                if (isPolishDue(config.getLastPolishTime()) && !polishGoods(config)) {
                    break;
                }
            }
        });
    }

    public void runReceiptFollowUps() {
        List<XianyuGoodsOrder> orders = goodsOrderMapper.selectDueReceiptFollowUps(100).stream()
                .filter(order -> goodsOrderMapper.claimReceiptFollowUp(order.getId(),
                        order.getReceiptFollowUpSentCount() == null ? 0 : order.getReceiptFollowUpSentCount()) == 1)
                .toList();
        Map<Long, List<XianyuGoodsOrder>> ordersByAccount = orders.stream()
                .filter(order -> order.getXianyuAccountId() != null && order.getOrderId() != null)
                .collect(Collectors.groupingBy(XianyuGoodsOrder::getXianyuAccountId));
        ordersByAccount.forEach(this::sendReceiptFollowUps);
    }

    private void sendReceiptFollowUps(Long accountId, List<XianyuGoodsOrder> orders) {
        Map<String, OrderRateDetailDTO> rateDetails = new HashMap<>();
        List<String> pendingOrderIds = orders.stream()
                .map(XianyuGoodsOrder::getOrderId)
                .toList();
        if (!pendingOrderIds.isEmpty()) {
            try {
                rateDetails = getRateDetails(accountId, pendingOrderIds).stream()
                        .collect(Collectors.toMap(OrderRateDetailDTO::getOrderId, detail -> detail,
                                (left, right) -> left));
            } catch (Exception e) {
                log.warn("【账号{}】检查买家确认收货状态失败: {}", accountId, e.getMessage());
            }
        }

        for (XianyuGoodsOrder order : orders) {
            try {
                int sentCount = order.getReceiptFollowUpSentCount() == null
                        ? 0 : order.getReceiptFollowUpSentCount();
                OrderRateDetailDTO detail = rateDetails.get(order.getOrderId());
                if (detail == null || !TRADE_COMPLETED.equals(detail.getTradeStatus())) {
                    goodsOrderMapper.deferReceiptFollowUp(order.getId(), LocalDateTime.now().plusMinutes(1));
                    continue;
                }
                if (Boolean.TRUE.equals(detail.getBuyerRated())) {
                    goodsOrderMapper.updateReceiptFollowUpProgress(
                            order.getId(), sentCount, 1, null, sentCount);
                    continue;
                }
                if (!Integer.valueOf(1).equals(order.getBuyerConfirmedReceipt())) {
                    // 平台交易成功代表买家已确认收货，状态落库后再进入逐条发送。
                    goodsOrderMapper.markBuyerConfirmedReceipt(order.getId());
                    order.setBuyerConfirmedReceipt(1);
                }

                XianyuGoodsAutoDeliveryConfig config = resolveReceiptFollowUpConfig(order);
                if (config == null) {
                    goodsOrderMapper.deferReceiptFollowUp(order.getId(), LocalDateTime.now().plusHours(1));
                    continue;
                }
                List<String> messages = buyerMessageService.parseReceiptFollowUpMessages(
                        config.getReceiptFollowUpMessages());
                if (messages.isEmpty() || sentCount >= messages.size()) {
                    goodsOrderMapper.updateReceiptFollowUpProgress(
                            order.getId(), sentCount, 1, null, sentCount);
                    continue;
                }

                boolean success = buyerMessageService.sendReceiptFollowUp(order, messages.get(sentCount));
                if (!success) {
                    goodsOrderMapper.deferReceiptFollowUp(order.getId(), LocalDateTime.now().plusSeconds(30));
                    continue;
                }

                int nextCount = sentCount + 1;
                boolean completed = nextCount >= messages.size();
                int intervalSeconds = buyerMessageService.normalizeReceiptFollowUpInterval(
                        config.getReceiptFollowUpIntervalSeconds());
                LocalDateTime nextTime = completed ? null : LocalDateTime.now().plusSeconds(intervalSeconds);
                goodsOrderMapper.updateReceiptFollowUpProgress(
                        order.getId(), nextCount, completed ? 1 : 0, nextTime, sentCount);
                operationLogService.log(accountId, OperationConstants.Type.SEND, OperationConstants.Module.ORDER,
                        "确认收货后话术发送成功", OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.ORDER, order.getOrderId(), messages.get(sentCount),
                        null, null, null);
            } catch (Exception e) {
                log.warn("【账号{}】发送确认收货后话术失败: orderId={}, error={}",
                        accountId, order.getOrderId(), e.getMessage());
                goodsOrderMapper.deferReceiptFollowUp(order.getId(), LocalDateTime.now().plusMinutes(1));
            }
        }
    }

    private XianyuGoodsAutoDeliveryConfig resolveReceiptFollowUpConfig(XianyuGoodsOrder order) {
        if (order.getSkuId() != null && !order.getSkuId().isBlank()) {
            XianyuGoodsAutoDeliveryConfig skuConfig = autoDeliveryConfigMapper
                    .findByAccountIdAndGoodsIdAndSkuId(
                            order.getXianyuAccountId(), order.getXyGoodsId(), order.getSkuId());
            if (hasReceiptFollowUpMessages(skuConfig)) {
                return skuConfig;
            }
        }
        XianyuGoodsAutoDeliveryConfig baseConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                order.getXianyuAccountId(), order.getXyGoodsId());
        return hasReceiptFollowUpMessages(baseConfig) ? baseConfig : null;
    }

    private boolean hasReceiptFollowUpMessages(XianyuGoodsAutoDeliveryConfig config) {
        return config != null && config.getReceiptFollowUpMessages() != null
                && !config.getReceiptFollowUpMessages().isBlank()
                && !"[]".equals(config.getReceiptFollowUpMessages());
    }

    private void ratePendingOrders(Long accountId, List<XianyuGoodsConfig> configs) {
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            return;
        }

        Map<String, Map<String, Object>> pendingItems = new LinkedHashMap<>();
        int startPage = rateScanStartPages.getOrDefault(accountId, 1);
        int nextScanPage = startPage;
        for (int offset = 0; offset < MAX_RATE_SCAN_PAGES; offset++) {
            int pageNumber = startPage + offset;
            Map<String, Object> rateSearchParam = new HashMap<>();
            rateSearchParam.put("sellerRateStatus", RATE_PENDING);
            Map<String, Object> request = new HashMap<>();
            request.put("pageNumber", pageNumber);
            request.put("rowsPerPage", RATE_DETAIL_PAGE_SIZE);
            request.put("queryType", "ORDER");
            request.put("rateSearchParam", rateSearchParam);

            XianyuApiCallUtils.ApiCallResult listResult = apiCallUtils.callApiWithRetry(
                    accountId, "mtop.taobao.idle.merchant.rate.list", request, cookie,
                    sellerHeaders(), sellerQueryParams());
            if (!listResult.isSuccess()) {
                log.warn("【账号{}】拉取待评价订单第{}页失败: {}", accountId, pageNumber, listResult.getErrorMessage());
                if (pageNumber == 1) {
                    return;
                }
                nextScanPage = pageNumber;
                break;
            }
            List<Map<String, Object>> pageItems = extractItems(listResult.extractData());
            for (Map<String, Object> item : pageItems) {
                String orderId = extractOrderId(item);
                if (orderId != null) {
                    pendingItems.putIfAbsent(orderId, item);
                }
            }
            if (pageItems.size() < RATE_DETAIL_PAGE_SIZE) {
                nextScanPage = 1;
                break;
            }
            nextScanPage = pageNumber + 1;
        }
        // 每轮从上次结束页继续扫描，到达末页后回到第一页，避免未评价订单长期阻塞深页。
        rateScanStartPages.put(accountId, nextScanPage);

        Map<String, XianyuGoodsConfig> configsByGoodsId = configs.stream()
                .collect(Collectors.toMap(XianyuGoodsConfig::getXyGoodsId, config -> config, (left, right) -> left));
        int attemptedCount = 0;
        // 先完成分页快照再回评，避免评价成功后列表收缩导致跨页订单被跳过。
        for (Map<String, Object> item : pendingItems.values()) {
            if (!isRateEligible(item)) {
                continue;
            }
            String orderId = extractOrderId(item);
            if (orderId == null) {
                continue;
            }
            XianyuGoodsOrder order = goodsOrderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            XianyuGoodsConfig config = order == null ? null : configsByGoodsId.get(order.getXyGoodsId());
            if (config == null) {
                continue;
            }
            if (attemptedCount >= MAX_RATE_PER_ACCOUNT) {
                break;
            }
            String content;
            try {
                content = ratingContentService.resolve(config.getXianyuAutoRateContent(), order);
            } catch (IllegalArgumentException e) {
                log.warn("【账号{}】自动评价文案无效，已跳过订单: orderId={}, error={}",
                        accountId, orderId, e.getMessage());
                continue;
            }
            attemptedCount++;
            XianyuApiCallUtils.ApiCallResult result = rateOrder(
                    accountId, orderId, order.getXyGoodsId(), content, "AUTO", cookie);
            if (!result.isSuccess() && isCredentialOrRiskFailure(result)) {
                break;
            }
            pauseBetweenRequests();
        }
    }

    /**
     * 手动评价复用自动评价调用链并记录文案来源
     */
    public void manualRate(Long accountId, String orderId, String feedback) {
        if (accountId == null || orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("账号和订单不能为空");
        }
        XianyuGoodsOrder order = goodsOrderMapper.selectByAccountIdAndOrderId(accountId, orderId.trim());
        if (order == null) {
            throw new IllegalArgumentException("订单不存在或无权操作");
        }
        String content = ratingContentService.renderTemplate(feedback, order);
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalStateException("账号 Cookie 无效，请先更新登录状态");
        }
        OrderRateDetailDTO detail = getRateDetails(accountId, List.of(order.getOrderId())).getFirst();
        if (Boolean.TRUE.equals(detail.getRated())) {
            String platformContent = detail.getSellerRates().isEmpty()
                    ? null : detail.getSellerRates().getFirst().getContent();
            goodsOrderMapper.updateRateResult(accountId, order.getOrderId(), 1, platformContent, "PLATFORM");
            throw new IllegalArgumentException("订单已经评价，无需重复操作");
        }
        if (!Boolean.TRUE.equals(detail.getCanRate())) {
            throw new IllegalArgumentException(detail.getStatusText());
        }
        XianyuApiCallUtils.ApiCallResult result = rateOrder(
                accountId, order.getOrderId(), order.getXyGoodsId(), content, "MANUAL", cookie);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getErrorMessage() == null ? "评价失败，请稍后重试" : result.getErrorMessage());
        }
    }

    private XianyuApiCallUtils.ApiCallResult rateOrder(Long accountId, String orderId, String goodsId, String feedback,
                                                       String source, String cookie) {
        Map<String, Object> request = new HashMap<>();
        request.put("tradeIdList", List.of(orderId));
        request.put("imageUrls", List.of());
        request.put("rate", 1);
        request.put("feedback", feedback);
        request.put("anonymous", false);

        XianyuApiCallUtils.ApiCallResult result = normalizeRateResult(apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idle.merchant.rate.create", request, cookie,
                sellerHeaders(), sellerQueryParams()), orderId);
        goodsOrderMapper.updateRateResult(accountId, orderId, result.isSuccess() ? 1 : -1, feedback, source);
        String action = "MANUAL".equals(source) ? "手动评价" : "自动评价";
        operationLogService.log(accountId, OperationConstants.Type.SEND, OperationConstants.Module.AUTO_RATE,
                result.isSuccess() ? action + "成功" : action + "失败",
                result.isSuccess() ? OperationConstants.Status.SUCCESS : OperationConstants.Status.FAIL,
                OperationConstants.TargetType.ORDER, orderId, feedback, result.getResponse(), result.getErrorMessage(), null);
        if (!result.isSuccess()) {
            log.warn("【账号{}】{}失败: orderId={}, goodsId={}, error={}",
                    accountId, action, orderId, goodsId, result.getErrorMessage());
        }
        return result;
    }

    /**
     * 批量读取平台评价，避免用本地发货状态推断交易评价结果。
     */
    public List<OrderRateDetailDTO> getRateDetails(Long accountId, List<String> orderIds) {
        if (accountId == null || orderIds == null || orderIds.isEmpty()) {
            throw new IllegalArgumentException("账号和订单不能为空");
        }
        Set<String> targets = orderIds.stream()
                .filter(orderId -> orderId != null && !orderId.isBlank())
                .map(String::trim)
                .limit(RATE_DETAIL_PAGE_SIZE)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("订单不能为空");
        }
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalStateException("账号 Cookie 无效，请先更新登录状态");
        }

        String exactOrderId = targets.size() == 1 ? targets.iterator().next() : null;
        XianyuApiCallUtils.ApiCallResult response = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idle.merchant.rate.list",
                buildRateListRequest("0", exactOrderId), cookie, sellerHeaders(), sellerQueryParams());
        if (!response.isSuccess()) {
            throw new IllegalStateException(response.getErrorMessage() == null
                    ? "评价状态同步失败" : response.getErrorMessage());
        }

        Map<String, OrderRateDetailDTO> details = new LinkedHashMap<>();
        for (Map<String, Object> item : extractItems(response.extractData())) {
            OrderRateDetailDTO detail = parseRateDetail(item);
            if (detail.getOrderId() != null && targets.contains(detail.getOrderId())) {
                details.put(detail.getOrderId(), detail);
            }
        }

        List<OrderRateDetailDTO> result = new ArrayList<>();
        for (String orderId : targets) {
            OrderRateDetailDTO detail = details.get(orderId);
            if (detail == null) {
                detail = new OrderRateDetailDTO();
                detail.setOrderId(orderId);
                detail.setSynced(false);
                detail.setStatusText("未查询到平台评价记录");
            }
            result.add(detail);
        }
        return result;
    }

    OrderRateDetailDTO parseRateDetail(Map<String, Object> item) {
        OrderRateDetailDTO detail = new OrderRateDetailDTO();
        if (item == null || !(item.get("merchantCommonData") instanceof Map<?, ?> commonData)) {
            detail.setSynced(false);
            detail.setStatusText("平台评价数据格式异常");
            return detail;
        }
        detail.setOrderId(stringValue(commonData.get("orderId")));
        detail.setTradeStatus(stringValue(commonData.get("orderStatus")));
        detail.setSellerRateStatus(stringValue(commonData.get("sellerRateStatus")));
        detail.setBuyerRateStatus(stringValue(commonData.get("buyerRateStatus")));
        if (item.get("rateItemVOList") instanceof List<?> rateItems) {
            for (Object value : rateItems) {
                if (!(value instanceof Map<?, ?> rateItem)) {
                    continue;
                }
                OrderRateDetailDTO.RateItemDTO rate = new OrderRateDetailDTO.RateItemDTO();
                rate.setContent(stringValue(rateItem.get("feedBack")));
                rate.setCreatedTime(stringValue(rateItem.get("gmtCreate")));
                rate.setLevel(integerValue(rateItem.get("rate")));
                rate.setMain(booleanValue(rateItem.get("main")));
                rate.setIllegal(booleanValue(rateItem.get("illegal")));
                if (booleanValue(rateItem.get("seller"))) {
                    detail.getSellerRates().add(rate);
                } else {
                    detail.getBuyerRates().add(rate);
                }
            }
        }
        boolean rated = RATE_COMPLETED.equals(detail.getSellerRateStatus())
                || detail.getSellerRates().stream().anyMatch(rate -> Boolean.TRUE.equals(rate.getMain()));
        boolean canRate = TRADE_COMPLETED.equals(detail.getTradeStatus())
                && RATE_PENDING.equals(detail.getSellerRateStatus()) && !rated;
        boolean buyerRated = RATE_COMPLETED.equals(detail.getBuyerRateStatus())
                || "已评价".equals(detail.getBuyerRateStatus()) || !detail.getBuyerRates().isEmpty();
        detail.setRated(rated);
        detail.setCanRate(canRate);
        detail.setBuyerRated(buyerRated);
        detail.setStatusText(resolveRateStatusText(detail));
        return detail;
    }

    XianyuApiCallUtils.ApiCallResult normalizeRateResult(XianyuApiCallUtils.ApiCallResult result, String orderId) {
        if (!result.isSuccess()) {
            return result;
        }
        // 平台外层调用成功不代表订单评价成功，必须校验业务结果。
        Map<String, Object> data = result.extractData();
        if (data == null || !(data.get("module") instanceof Map<?, ?> module)) {
            return new XianyuApiCallUtils.ApiCallResult(false, result.getResponse(),
                    "评价结果缺少业务状态", false);
        }
        boolean success = booleanValue(module.get("success"));
        boolean orderSucceeded = false;
        if (module.get("successOrderIds") instanceof List<?> successOrderIds) {
            orderSucceeded = successOrderIds.stream().anyMatch(value -> orderId.equals(stringValue(value)));
        }
        String errorMessage = extractRateError(module, orderId);
        if (success && (orderSucceeded || errorMessage == null)) {
            return result;
        }
        return new XianyuApiCallUtils.ApiCallResult(false, result.getResponse(),
                errorMessage == null ? "评价未被平台确认" : errorMessage, false);
    }

    private Map<String, Object> buildRateListRequest(String sellerRateStatus, String orderId) {
        Map<String, Object> rateSearchParam = new HashMap<>();
        rateSearchParam.put("sellerRateStatus", sellerRateStatus);
        if (orderId != null && !orderId.isBlank()) {
            // 单订单查询使用平台精确筛选，避免遍历历史评价记录。
            rateSearchParam.put("orderId", orderId);
        }
        Map<String, Object> request = new HashMap<>();
        request.put("pageNumber", 1);
        request.put("rowsPerPage", RATE_DETAIL_PAGE_SIZE);
        request.put("queryType", "ORDER");
        request.put("rateSearchParam", rateSearchParam);
        return request;
    }

    private boolean isRateEligible(Map<String, Object> item) {
        return isAutoRateEligible(parseRateDetail(item));
    }

    boolean isAutoRateEligible(OrderRateDetailDTO detail) {
        return Boolean.TRUE.equals(detail.getCanRate()) && Boolean.TRUE.equals(detail.getBuyerRated());
    }

    private String resolveRateStatusText(OrderRateDetailDTO detail) {
        if (Boolean.TRUE.equals(detail.getRated())) {
            return "已评价";
        }
        if (Boolean.TRUE.equals(detail.getCanRate())) {
            return "待评价";
        }
        if ("已发货".equals(detail.getTradeStatus())) {
            return "等待买家确认收货";
        }
        if ("交易关闭".equals(detail.getTradeStatus())) {
            return "订单已关闭，无需评价";
        }
        return detail.getTradeStatus() == null || detail.getTradeStatus().isBlank()
                ? "评价状态待同步" : detail.getTradeStatus() + "，暂不可评价";
    }

    private String extractRateError(Map<?, ?> module, String orderId) {
        if (!(module.get("failOrderInfos") instanceof List<?> failOrderInfos)) {
            return null;
        }
        for (Object value : failOrderInfos) {
            if (value instanceof Map<?, ?> failure
                    && (orderId.equals(stringValue(failure.get("orderId"))) || failOrderInfos.size() == 1)) {
                return stringValue(failure.get("errorMsg"));
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean polishGoods(XianyuGoodsConfig config) {
        Long accountId = config.getXianyuAccountId();
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            return false;
        }

        Map<String, Object> request = Map.of("itemId", config.getXyGoodsId());
        Map<String, String> query = new HashMap<>(sellerQueryParams());
        query.put("v", "2.0");
        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idle.item.polish", request, cookie, sellerHeaders(), query);
        boolean completed = result.isSuccess() || isAlreadyPolished(result.getErrorMessage());
        if (completed) {
            goodsConfigMapper.updateLastPolishTime(config.getId(), clock.millis());
        }
        operationLogService.log(accountId, OperationConstants.Type.UPDATE, OperationConstants.Module.AUTO_POLISH,
                completed ? "商品自动擦亮完成" : "商品自动擦亮失败",
                completed ? OperationConstants.Status.SUCCESS : OperationConstants.Status.FAIL,
                OperationConstants.TargetType.GOODS, config.getXyGoodsId(), null,
                result.getResponse(), result.getErrorMessage(), null);
        pauseBetweenRequests();
        return completed || !isCredentialOrRiskFailure(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> data) {
        if (data == null || !(data.get("module") instanceof Map<?, ?> module)
                || !(module.get("items") instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    String extractOrderId(Map<String, Object> item) {
        if (item == null || !(item.get("merchantCommonData") instanceof Map<?, ?> commonData)) {
            return null;
        }
        Object orderId = commonData.get("orderId");
        return orderId == null ? null : String.valueOf(orderId);
    }

    boolean isPolishDue(Long lastPolishTime) {
        if (lastPolishTime == null) {
            return true;
        }
        LocalDate lastDate = Instant.ofEpochMilli(lastPolishTime).atZone(BUSINESS_ZONE).toLocalDate();
        return lastDate.isBefore(LocalDate.now(clock));
    }

    private boolean isAlreadyPolished(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        return errorMessage.contains("已经擦亮") || errorMessage.contains("一天只能擦亮一次")
                || errorMessage.contains("POLISH_AGAIN") || errorMessage.contains("POLISH_DUPLICATE");
    }

    private boolean isCredentialOrRiskFailure(XianyuApiCallUtils.ApiCallResult result) {
        if (result.isTokenExpired()) {
            return true;
        }
        String error = result.getErrorMessage();
        return error != null && (error.contains("异常流量") || error.contains("风控")
                || error.contains("滑块") || error.contains("验证") || error.contains("FAIL_SYS_USER_VALIDATE"));
    }

    private Map<String, String> sellerHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("idle_site_biz_code", "COMMONPRO");
        headers.put("Origin", "https://seller.goofish.com");
        headers.put("Referer", "https://seller.goofish.com/?site=COMMONPRO");
        return headers;
    }

    private Map<String, String> sellerQueryParams() {
        Map<String, String> query = new HashMap<>();
        query.put("type", "json");
        query.put("valueType", "string");
        return query;
    }

    private void pauseBetweenRequests() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
