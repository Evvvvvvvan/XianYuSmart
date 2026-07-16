package com.xianyusmart.service;

import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品评价和擦亮自动化服务
 */
@Slf4j
@Service
public class GoodsAutomationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String RATE_CONTENT = "交易愉快，感谢支持，期待再次合作。满意的话欢迎点亮小红花。";
    private static final int MAX_RATE_PER_ACCOUNT = 20;

    private final XianyuGoodsConfigMapper goodsConfigMapper;
    private final XianyuGoodsOrderMapper goodsOrderMapper;
    private final AccountService accountService;
    private final XianyuApiCallUtils apiCallUtils;
    private final OperationLogService operationLogService;
    private final Clock clock;

    @Autowired
    public GoodsAutomationService(XianyuGoodsConfigMapper goodsConfigMapper,
                                  XianyuGoodsOrderMapper goodsOrderMapper,
                                  AccountService accountService,
                                  XianyuApiCallUtils apiCallUtils,
                                  OperationLogService operationLogService) {
        this(goodsConfigMapper, goodsOrderMapper, accountService, apiCallUtils, operationLogService,
                Clock.system(BUSINESS_ZONE));
    }

    GoodsAutomationService(XianyuGoodsConfigMapper goodsConfigMapper,
                           XianyuGoodsOrderMapper goodsOrderMapper,
                           AccountService accountService,
                           XianyuApiCallUtils apiCallUtils,
                           OperationLogService operationLogService,
                           Clock clock) {
        this.goodsConfigMapper = goodsConfigMapper;
        this.goodsOrderMapper = goodsOrderMapper;
        this.accountService = accountService;
        this.apiCallUtils = apiCallUtils;
        this.operationLogService = operationLogService;
        this.clock = clock;
    }

    public void runAutoRate() {
        Map<Long, List<XianyuGoodsConfig>> configsByAccount = goodsConfigMapper.selectAutoRateEnabled().stream()
                .filter(config -> config.getXianyuAccountId() != null && config.getXyGoodsId() != null)
                .collect(Collectors.groupingBy(XianyuGoodsConfig::getXianyuAccountId));
        configsByAccount.forEach(this::ratePendingOrders);
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

    private void ratePendingOrders(Long accountId, List<XianyuGoodsConfig> configs) {
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            return;
        }

        Map<String, Object> rateSearchParam = new HashMap<>();
        rateSearchParam.put("sellerRateStatus", "5");
        Map<String, Object> request = new HashMap<>();
        request.put("pageNumber", 1);
        request.put("rowsPerPage", MAX_RATE_PER_ACCOUNT);
        request.put("queryType", "ORDER");
        request.put("rateSearchParam", rateSearchParam);

        XianyuApiCallUtils.ApiCallResult listResult = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idle.merchant.rate.list", request, cookie,
                sellerHeaders(), sellerQueryParams());
        if (!listResult.isSuccess()) {
            log.warn("【账号{}】拉取待评价订单失败: {}", accountId, listResult.getErrorMessage());
            return;
        }

        Set<String> enabledGoodsIds = configs.stream()
                .map(XianyuGoodsConfig::getXyGoodsId)
                .collect(Collectors.toCollection(HashSet::new));
        for (Map<String, Object> item : extractItems(listResult.extractData())) {
            String orderId = extractOrderId(item);
            if (orderId == null) {
                continue;
            }
            XianyuGoodsOrder order = goodsOrderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            if (order == null || !enabledGoodsIds.contains(order.getXyGoodsId())) {
                continue;
            }
            if (!rateOrder(accountId, orderId, order.getXyGoodsId(), cookie)) {
                break;
            }
            pauseBetweenRequests();
        }
    }

    private boolean rateOrder(Long accountId, String orderId, String goodsId, String cookie) {
        Map<String, Object> request = new HashMap<>();
        request.put("tradeId", orderId);
        request.put("rate", 1);
        request.put("feedback", RATE_CONTENT);
        request.put("createOrAppend", 0);

        XianyuApiCallUtils.ApiCallResult result = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idle.rate.create", "4.0", request, cookie,
                sellerHeaders(), sellerQueryParams());
        goodsOrderMapper.updateRateStatus(accountId, orderId, result.isSuccess() ? 1 : -1);
        operationLogService.log(accountId, OperationConstants.Type.SEND, OperationConstants.Module.AUTO_RATE,
                result.isSuccess() ? "自动评价成功" : "自动评价失败",
                result.isSuccess() ? OperationConstants.Status.SUCCESS : OperationConstants.Status.FAIL,
                OperationConstants.TargetType.ORDER, orderId, null, result.getResponse(), result.getErrorMessage(), null);
        if (!result.isSuccess()) {
            log.warn("【账号{}】自动评价失败: orderId={}, goodsId={}, error={}",
                    accountId, orderId, goodsId, result.getErrorMessage());
        }
        return result.isSuccess() || !isCredentialOrRiskFailure(result);
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
