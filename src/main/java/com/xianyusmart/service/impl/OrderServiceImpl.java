package com.xianyusmart.service.impl;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.BuyerMessageService;
import com.xianyusmart.service.KamiConfigService;
import com.xianyusmart.service.OrderService;
import com.xianyusmart.service.delivery.DeliveryContext;
import com.xianyusmart.service.delivery.DeliveryStrategyResolver;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xianyusmart.service.AccountService;

/**
 * 订单服务实现
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {
    
    @Autowired
    private AccountService accountService;
    
    @Autowired
    private XianyuApiCallUtils xianyuApiCallUtils;

    @Autowired
    private XianyuGoodsOrderMapper orderMapper;

    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private DeliveryStrategyResolver deliveryStrategyResolver;

    @Autowired
    private KamiConfigService kamiConfigService;

    @Autowired
    private BuyerMessageService buyerMessageService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    @Override
    public String confirmShipment(Long accountId, String orderId) {
        return confirmShipmentToXianyu(accountId, orderId);
    }

    @Override
    public String consignDummyDelivery(Long accountId, String orderId, String tradeText, List<String> imageUrls) {
        try {
            log.info("【账号{}】开始调用闲鱼新发货API(虚拟发货): orderId={}", accountId, orderId);

            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return null;
            }

            String limitedText = tradeText;
            if (limitedText != null && limitedText.length() > 200) {
                limitedText = limitedText.substring(0, 200);
                log.info("【账号{}】发货内容超过200字，已截断", accountId);
            }

            List<String> limitedImages = new ArrayList<>();
            if (imageUrls != null && !imageUrls.isEmpty()) {
                int limit = Math.min(imageUrls.size(), 3);
                limitedImages = imageUrls.subList(0, limit);
                if (imageUrls.size() > 3) {
                    log.info("【账号{}】发货图片超过3张，已截断", accountId);
                }
            }

            String picListJson;
            if (limitedImages.isEmpty()) {
                picListJson = "[]";
            } else {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < limitedImages.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(limitedImages.get(i)).append("\"");
                }
                sb.append("]");
                picListJson = sb.toString();
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("orderId", orderId);
            dataMap.put("tradeText", limitedText != null ? limitedText : "");
            dataMap.put("picList", picListJson);
            dataMap.put("newUnconsign", true);

            Map<String, String> extraHeaders = new HashMap<>();
            extraHeaders.put("idle_site_biz_code", "COMMONPRO");
            extraHeaders.put("Origin", "https://seller.goofish.com");
            extraHeaders.put("Referer", "https://seller.goofish.com/?site=COMMONPRO");

            XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
                    accountId,
                    "mtop.taobao.idle.logistics.merchant.consign.dummy",
                    dataMap,
                    cookieStr,
                    extraHeaders
            );

            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                log.error("【账号{}】❌ 闲鱼新发货API失败: {}", accountId, errorMsg);

                if (result.isTokenExpired()) {
                    return null;
                }

                if (errorMsg != null && errorMsg.contains("ORDER_ALREADY_DELIVERY")) {
                    log.info("【账号{}】订单已存在发货凭证: orderId={}", accountId, orderId);
                    return CONSIGN_ALREADY_DELIVERED;
                }

                if (isUncertainConsignResult(result)) {
                    log.warn("【账号{}】发货接口结果不确定，保留本次发货内容等待核对: orderId={}", accountId, orderId);
                    return CONSIGN_UNCERTAIN;
                }

                return null;
            }

            Map<String, Object> responseData = result.extractData();
            if (responseData != null) {
                log.info("【账号{}】✅ 闲鱼新发货API成功: orderId={}", accountId, orderId);
                return CONSIGN_SUCCESS;
            } else {
                log.error("【账号{}】响应数据格式错误", accountId);
                return CONSIGN_UNCERTAIN;
            }

        } catch (Exception e) {
            log.error("【账号{}】调用闲鱼新发货API异常: orderId={}", accountId, orderId, e);
            return CONSIGN_UNCERTAIN;
        }
    }

    private boolean isUncertainConsignResult(XianyuApiCallUtils.ApiCallResult result) {
        if (result.getResponse() == null || result.getResponse().isBlank()) {
            return true;
        }
        String errorMessage = result.getErrorMessage();
        return errorMessage != null && (errorMessage.contains("响应为空")
                || errorMessage.contains("响应格式错误")
                || errorMessage.contains("调用异常"));
    }
    
    @Override
    public String confirmShipmentToXianyu(Long accountId, String orderId) {
        try {
            log.info("【账号{}】开始调用闲鱼API确认发货: orderId={}", accountId, orderId);
            
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return null;
            }
            
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("orderId", orderId);
            dataMap.put("tradeText", "");
            dataMap.put("picList", new String[0]);
            dataMap.put("newUnconsign", true);
            
            log.info("【账号{}】data参数: {}", accountId, dataMap);
            
            XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
                    accountId, 
                    "mtop.taobao.idle.logistic.consign.dummy", 
                    dataMap, 
                    cookieStr
            );
            
            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                log.error("【账号{}】❌ 闲鱼API确认发货失败: {}", accountId, errorMsg);
                
                if (result.isTokenExpired()) {
                    return "令牌过期，请稍后重试或手动更新Cookie";
                }

                if (errorMsg != null && errorMsg.contains("ORDER_ALREADY_DELIVERY")) {
                    log.info("【账号{}】订单已发货(ORDER_ALREADY_DELIVERY)，视为确认成功: orderId={}", accountId, orderId);
                    return "确认发货成功(已发货)";
                }
                
                return null;
            }
            
            Map<String, Object> responseData = result.extractData();
            if (responseData != null) {
                log.info("【账号{}】✅ 闲鱼API确认发货成功: orderId={}", accountId, orderId);
                return "确认发货成功";
            } else {
                log.error("【账号{}】响应数据格式错误", accountId);
                return null;
            }
            
        } catch (Exception e) {
            log.error("【账号{}】调用闲鱼API确认发货异常: orderId={}", accountId, orderId, e);
            return null;
        }
    }

    @Override
    public String getOrderDetail(Long accountId, String orderId) {
        try {
            log.info("【账号{}】开始获取订单详情: orderId={}", accountId, orderId);

            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return null;
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("tid", orderId);

            XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
                    accountId,
                    "mtop.taobao.idle.trade.merchant.full.info",
                    dataMap,
                    cookieStr
            );

            if (!result.isSuccess()) {
                log.warn("【账号{}】获取订单详情失败: orderId={}, error={}", accountId, orderId, result.getErrorMessage());
                return null;
            }

            Map<String, Object> responseData = result.extractData();
            if (responseData == null) {
                log.warn("【账号{}】订单详情响应数据为空: orderId={}", accountId, orderId);
                return null;
            }

            String json = objectMapper.writeValueAsString(responseData);
            log.info("【账号{}】获取订单详情成功: orderId={}", accountId, orderId);

            updateOrderDetailFromApi(accountId, orderId, responseData);

            return json;
        } catch (Exception e) {
            log.error("【账号{}】获取订单详情异常: orderId={}", accountId, orderId, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void updateOrderDetailFromApi(Long accountId, String orderId, Map<String, Object> responseData) {
        try {
            XianyuGoodsOrder order = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            if (order == null) {
                log.debug("【账号{}】本地无此订单记录，跳过更新: orderId={}", accountId, orderId);
                return;
            }

            Object moduleObj = responseData.get("module");
            if (!(moduleObj instanceof Map)) return;
            Map<String, Object> module = (Map<String, Object>) moduleObj;

            String buyerUserName = null;
            Object merchantBuyerVO = module.get("merchantBuyerVO");
            if (merchantBuyerVO instanceof Map) {
                Map<String, Object> buyer = (Map<String, Object>) merchantBuyerVO;
                Object userNick = buyer.get("userNick");
                if (userNick instanceof String) buyerUserName = (String) userNick;
            }

            String orderCreateTime = null;
            String paySuccessTime = null;
            String consignTime = null;
            Object merchantCommonData = module.get("merchantCommonData");
            if (merchantCommonData instanceof Map) {
                Map<String, Object> commonData = (Map<String, Object>) merchantCommonData;
                Object ct = commonData.get("createTime");
                if (ct instanceof String) orderCreateTime = (String) ct;
                Object pt = commonData.get("paySuccessTime");
                if (pt instanceof String) paySuccessTime = (String) pt;
                Object ct2 = commonData.get("consignTime");
                if (ct2 instanceof String) consignTime = (String) ct2;
            }

            String goodsTitle = null;
            Object merchantItemVO = module.get("merchantItemVO");
            if (merchantItemVO instanceof Map) {
                Map<String, Object> merchantItem = (Map<String, Object>) merchantItemVO;
                Object title = merchantItem.get("title");
                if (title instanceof String) goodsTitle = (String) title;
            }

            String totalPrice = null;
            Integer buyNum = null;
            Object merchantPriceVO = module.get("merchantPriceVO");
            if (merchantPriceVO instanceof Map) {
                Map<String, Object> priceVO = (Map<String, Object>) merchantPriceVO;
                Object tp = priceVO.get("totalPrice");
                if (tp instanceof String) totalPrice = (String) tp;
                Object bn = priceVO.get("buyNum");
                if (bn instanceof String) {
                    try { buyNum = Integer.parseInt((String) bn); } catch (Exception e) { buyNum = 1; }
                } else if (bn instanceof Number) {
                    buyNum = ((Number) bn).intValue();
                }
            }

            orderMapper.updateOrderDetail(order.getId(), buyerUserName, orderCreateTime, paySuccessTime, consignTime, null, null, goodsTitle, totalPrice, buyNum);
            log.info("【账号{}】从API更新订单详情成功: orderId={}", accountId, orderId);
        } catch (Exception e) {
            log.warn("【账号{}】更新订单详情失败: orderId={}", accountId, orderId, e);
        }
    }

    @Override
    public String getOrderDetailFromLocal(Long accountId, String orderId) {
        try {
            XianyuGoodsOrder order = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            if (order == null) {
                log.warn("【账号{}】本地未找到订单: orderId={}", accountId, orderId);
                return null;
            }
            Map<String, Object> result = new HashMap<>();
            result.put("orderId", order.getOrderId());
            result.put("xyGoodsId", order.getXyGoodsId());
            result.put("goodsTitle", order.getGoodsTitle());
            result.put("buyerUserName", order.getBuyerUserName());
            result.put("content", order.getContent());
            result.put("state", order.getState());
            result.put("failReason", order.getFailReason());
            result.put("confirmState", order.getConfirmState());
            result.put("createTime", order.getCreateTime());
            result.put("skuName", order.getSkuName());
            result.put("orderCreateTime", order.getOrderCreateTime());
            result.put("paySuccessTime", order.getPaySuccessTime());
            result.put("consignTime", order.getConsignTime());
            result.put("totalPrice", order.getTotalPrice());
            result.put("buyNum", order.getBuyNum());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("【账号{}】获取本地订单详情异常: orderId={}", accountId, orderId, e);
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryPendingOrders(Long accountId) {
        try {
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到Cookie", accountId);
                return List.of();
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("pageNumber", 1);
            dataMap.put("rowsPerPage", 20);
            dataMap.put("orderIds", "");
            dataMap.put("queryCode", "NOT_SHIP");
            dataMap.put("orderSearchParam", "{}");

            Map<String, String> extraHeaders = new HashMap<>();
            extraHeaders.put("idle_site_biz_code", "COMMONPRO");
            extraHeaders.put("Origin", "https://seller.goofish.com");
            extraHeaders.put("Referer", "https://seller.goofish.com/?site=COMMONPRO");

            Map<String, String> extraQueryParams = new HashMap<>();
            extraQueryParams.put("type", "json");
            extraQueryParams.put("valueType", "string");

            XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
                    accountId,
                    "mtop.taobao.idle.trade.merchant.sold.get",
                    dataMap,
                    cookieStr,
                    extraHeaders,
                    extraQueryParams
            );

            if (!result.isSuccess()) {
                // 鱼小铺订单接口对普通账号返回无权限，自动切换通用卖家订单接口。
                if (isPermissionDenied(result)) {
                    return queryStandardPendingOrders(accountId, cookieStr);
                }
                log.warn("【账号{}】查询待发货订单失败: {}", accountId, result.getErrorMessage());
                return List.of();
            }

            Map<String, Object> responseData = result.extractData();
            if (responseData == null) {
                return List.of();
            }

            Object moduleObj = responseData.get("module");
            if (!(moduleObj instanceof Map)) return List.of();
            Map<String, Object> module = (Map<String, Object>) moduleObj;

            Object itemsObj = module.get("items");
            if (!(itemsObj instanceof List)) return List.of();
            return (List<Map<String, Object>>) itemsObj;
        } catch (Exception e) {
            log.error("【账号{}】查询待发货订单异常", accountId, e);
            return List.of();
        }
    }

    private boolean isPermissionDenied(XianyuApiCallUtils.ApiCallResult result) {
        String errorMessage = result.getErrorMessage();
        return errorMessage != null && errorMessage.startsWith("PERMISSION_EXCEPTION");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryStandardPendingOrders(Long accountId, String cookieStr) {
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("pageNumber", 1);
        dataMap.put("orderStatus", "NOT_SHIP");
        dataMap.put("offsetRow", 0);

        Map<String, String> extraHeaders = new HashMap<>();
        extraHeaders.put("Origin", "https://h5.m.goofish.com");
        extraHeaders.put("Referer", "https://h5.m.goofish.com/");

        Map<String, String> extraQueryParams = new HashMap<>();
        extraQueryParams.put("type", "originaljson");
        extraQueryParams.put("valueType", "original");

        XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
                accountId,
                "mtop.taobao.idle.trade.sold.get",
                "5.0",
                dataMap,
                cookieStr,
                extraHeaders,
                extraQueryParams
        );

        if (!result.isSuccess()) {
            log.warn("【账号{}】普通卖家待发货订单查询失败: {}", accountId, result.getErrorMessage());
            return List.of();
        }

        Map<String, Object> responseData = result.extractData();
        if (responseData == null || !(responseData.get("items") instanceof List)) {
            return List.of();
        }

        List<Map<String, Object>> orders = new ArrayList<>();
        for (Object item : (List<?>) responseData.get("items")) {
            if (item instanceof Map) {
                Map<String, Object> order = (Map<String, Object>) item;
                String orderId = toStringValue(order.get("bizOrderId"));
                String itemId = toStringValue(order.get("auctionId"));
                // 以真实状态校验待发货，防止接口忽略筛选条件后误入自动发货队列。
                if (isStandardPendingOrder(order) && hasText(orderId) && hasText(itemId)) {
                    orders.add(normalizeStandardOrder(order));
                }
            }
        }
        log.info("【账号{}】已通过普通卖家接口获取待发货订单: count={}", accountId, orders.size());
        return orders;
    }

    private Map<String, Object> normalizeStandardOrder(Map<String, Object> order) {
        // 统一为现有待发货结构，避免下游发货逻辑感知账号类型。
        Map<String, Object> commonData = new HashMap<>();
        commonData.put("orderId", toStringValue(order.get("bizOrderId")));
        commonData.put("itemId", toStringValue(order.get("auctionId")));
        commonData.put("orderStatus", "待发货");
        commonData.put("createTime", order.get("createTime"));

        Map<String, Object> buyerInfo = new HashMap<>();
        buyerInfo.put("userId", toStringValue(order.get("buyerId")));
        buyerInfo.put("userNick", order.get("buyerNick"));

        Map<String, Object> itemInfo = new HashMap<>();
        itemInfo.put("title", order.get("auctionTitle"));

        Map<String, Object> priceInfo = new HashMap<>();
        priceInfo.put("totalPrice", toStringValue(order.get("totalFee")));
        priceInfo.put("buyNum", order.get("buyAmount"));

        Map<String, Object> normalized = new HashMap<>();
        normalized.put("commonData", commonData);
        normalized.put("buyerInfoVO", buyerInfo);
        normalized.put("itemVO", itemInfo);
        normalized.put("priceVO", priceInfo);
        return normalized;
    }

    private boolean isStandardPendingOrder(Map<String, Object> order) {
        String orderStatus = toStringValue(order.get("orderStatus"));
        if (orderStatus == null) {
            orderStatus = toStringValue(order.get("status"));
        }
        if ("2".equals(orderStatus)) {
            return true;
        }

        String orderStatusMsg = toStringValue(order.get("orderStatusMsg"));
        if (orderStatusMsg == null) {
            orderStatusMsg = toStringValue(order.get("statusMsg"));
        }
        if (orderStatusMsg == null) {
            return false;
        }
        String normalizedStatusMsg = orderStatusMsg.trim();
        return "待发货".equals(normalizedStatusMsg) || "等待卖家发货".equals(normalizedStatusMsg);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public Map<String, Object> getOrderDetailMap(Long accountId, String orderId) {
        try {
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                return null;
            }

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("tid", orderId);

            XianyuApiCallUtils.ApiCallResult result = xianyuApiCallUtils.callApiWithRetry(
                    accountId,
                    "mtop.taobao.idle.trade.merchant.full.info",
                    dataMap,
                    cookieStr
            );

            if (!result.isSuccess()) {
                log.warn("【账号{}】获取订单详情失败: orderId={}, error={}", accountId, orderId, result.getErrorMessage());
                return null;
            }

            return result.extractData();
        } catch (Exception e) {
            log.warn("【账号{}】获取订单详情异常: orderId={}", accountId, orderId, e);
            return null;
        }
    }

    @Override
    public String consignDummyDeliveryWithConfig(Long accountId, String xyGoodsId, String orderId) {
        log.info("【账号{}】按商品配置发货: xyGoodsId={}, orderId={}", accountId, xyGoodsId, orderId);

        XianyuGoodsOrder existingOrder = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
        XianyuGoodsAutoDeliveryConfig deliveryConfig = null;
        if (existingOrder != null && existingOrder.getSkuId() != null && !existingOrder.getSkuId().isBlank()) {
            deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdAndSkuId(
                    accountId, xyGoodsId, existingOrder.getSkuId());
        }
        if (deliveryConfig == null) {
            deliveryConfig = autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(accountId, xyGoodsId);
        }

        if (deliveryConfig == null) {
            log.warn("【账号{}】商品无发货配置，请先配置自动发货: xyGoodsId={}", accountId, xyGoodsId);
            throw new BusinessException(89282,"商品无发货配置，请先配置自动发货");
        }

        int deliveryMode = deliveryConfig.getDeliveryMode() != null ? deliveryConfig.getDeliveryMode() : 1;
        boolean cardDelivery = deliveryMode == 2;
        boolean voucherDeliveryEnabled = !Integer.valueOf(0).equals(deliveryConfig.getVoucherDeliveryEnabled());
        boolean chatDeliveryEnabled = !Integer.valueOf(0).equals(deliveryConfig.getChatDeliveryEnabled());

        // 从订单记录中获取买家信息，保证模板变量和卡密记录一致。
        String sId = null;
        String buyerUserName = null;
        if (existingOrder != null) {
            sId = existingOrder.getSid();
            buyerUserName = existingOrder.getBuyerUserName();
        }
        if (sId == null || sId.isEmpty()) {
            sId = orderId; // fallback
        }

        DeliveryContext ctx = DeliveryContext.builder()
                .accountId(accountId)
                .xyGoodsId(xyGoodsId)
                .orderId(orderId)
                .sId(sId)
                .buyerUserName(buyerUserName)
                .quantity(existingOrder != null && existingOrder.getBuyNum() != null ? existingOrder.getBuyNum() : 1)
                .deliveryConfig(deliveryConfig)
                .build();

        String content = deliveryStrategyResolver.resolve(deliveryMode, ctx);
        if (content == null) {
            String failMsg = deliveryMode == 1 ? "未配置固定发货模板" : "卡密库存不足，无可用卡密";
            log.warn("【账号{}】发货内容解析失败: {}", accountId, failMsg);
            return null;
        }

        String messageTemplate = deliveryMode == 1
                ? ctx.getFixedTemplate().getMessageTemplate()
                : deliveryConfig.getDeliveryMessageTemplate();
        String finalDeliveryContent = buyerMessageService.renderVariables(
                buyerMessageService.normalizeDeliveryMessageTemplate(messageTemplate),
                buyerUserName, orderId, content);

        if (voucherDeliveryEnabled && finalDeliveryContent.length() > 200) {
            if (cardDelivery) {
                kamiConfigService.releaseReservation(orderId);
            }
            log.warn("【账号{}】渲染后的发货内容超过凭证接口限制: orderId={}, contentLen={}",
                    accountId, orderId, finalDeliveryContent.length());
            return null;
        }

        List<String> imageUrls = new ArrayList<>();
        String imageUrlStr = deliveryConfig.getAutoDeliveryImageUrl();
        if (imageUrlStr != null && !imageUrlStr.trim().isEmpty()) {
            for (String url : imageUrlStr.split(",")) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) imageUrls.add(trimmed);
            }
        }

        boolean cardReservationCommitted = false;
        boolean deliveryMessageHeld = false;
        XianyuGoodsOrder deliveryOrder = existingOrder;
        try {
            if (deliveryOrder == null) {
                deliveryOrder = new XianyuGoodsOrder();
                deliveryOrder.setXianyuAccountId(accountId);
                deliveryOrder.setXyGoodsId(xyGoodsId);
                deliveryOrder.setOrderId(orderId);
                deliveryOrder.setPnmId("api_" + orderId);
                deliveryOrder.setContent(finalDeliveryContent);
                deliveryOrder.setState(0);
                deliveryOrder.setConfirmState(0);
                orderMapper.insert(deliveryOrder);
            }

            if (chatDeliveryEnabled && (cardDelivery || voucherDeliveryEnabled)) {
                boolean held = cardDelivery
                        ? buyerMessageService.holdDeliveryMessage(deliveryOrder, finalDeliveryContent)
                        : buyerMessageService.holdFixedDeliveryMessage(deliveryOrder, finalDeliveryContent);
                if (!held) {
                    throw new IllegalStateException("发货私聊暂存失败");
                }
                deliveryMessageHeld = true;
            }

            if (voucherDeliveryEnabled) {
                log.info("【账号{}】先提交发货凭证: orderId={}, deliveryMode={}, contentLen={}, imageCount={}",
                        accountId, orderId, deliveryMode, finalDeliveryContent.length(), imageUrls.size());
                String result = consignDummyDelivery(accountId, orderId, finalDeliveryContent, imageUrls);
                if (CONSIGN_UNCERTAIN.equals(result) || CONSIGN_ALREADY_DELIVERED.equals(result)) {
                    String failReason = CONSIGN_UNCERTAIN.equals(result)
                            ? "发货结果待确认，请核对平台凭证后处理"
                            : "订单已存在发货凭证，请核对凭证与私聊内容";
                    if (cardDelivery) {
                        // 凭证状态不明确时锁定卡密，避免人工核对前再次分配。
                        kamiConfigService.markReservationReviewRequired(orderId);
                    }
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    orderMapper.updateStateContentAndFailReason(
                            deliveryOrder.getId(), -1, finalDeliveryContent, failReason);
                    orderMapper.markTaskReviewRequired(deliveryOrder.getId(), failReason);
                    return null;
                }
                if (!CONSIGN_SUCCESS.equals(result)) {
                    if (cardDelivery) {
                        kamiConfigService.releaseReservation(orderId);
                    }
                    if (deliveryMessageHeld) {
                        buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                        deliveryMessageHeld = false;
                    }
                    return null;
                }
                if (deliveryMessageHeld && !cardDelivery) {
                    if (orderMapper.confirmFixedHeldDeliveryMessage(accountId, orderId) != 1) {
                        throw new IllegalStateException("固定内容凭证状态确认失败");
                    }
                    deliveryOrder.setDeliveryMessageState(5);
                } else {
                    orderMapper.updateConfirmState(accountId, orderId);
                }
                deliveryOrder.setConfirmState(1);
            }

            if (cardDelivery) {
                // 卡密提交成功后，暂存私聊才允许进入发送队列。
                kamiConfigService.commitReservation(orderId, accountId, xyGoodsId,
                        deliveryOrder.getBuyerUserId(), buyerUserName);
                cardReservationCommitted = true;
            }

            orderMapper.updateStateAndContent(deliveryOrder.getId(), 1, finalDeliveryContent);
            deliveryOrder.setContent(finalDeliveryContent);
            deliveryOrder.setState(1);

            if (chatDeliveryEnabled) {
                boolean messageSent = deliveryMessageHeld
                        ? buyerMessageService.activateAndSendDeliveryMessage(deliveryOrder)
                        : buyerMessageService.queueDeliveryMessage(deliveryOrder, finalDeliveryContent);
                if (!messageSent) {
                    log.info("【账号{}】发货私聊已进入重试队列: orderId={}", accountId, orderId);
                }
            }
        } catch (RuntimeException e) {
            if (!cardDelivery && deliveryMessageHeld
                    && !Integer.valueOf(5).equals(deliveryOrder.getDeliveryMessageState())) {
                buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                deliveryMessageHeld = false;
            }
            if (cardDelivery && !cardReservationCommitted) {
                if (deliveryMessageHeld) {
                    buyerMessageService.cancelHeldDeliveryMessage(deliveryOrder);
                    deliveryMessageHeld = false;
                }
                if (voucherDeliveryEnabled) {
                    kamiConfigService.markReservationReviewRequired(orderId);
                } else {
                    kamiConfigService.releaseReservation(orderId);
                }
            }
            throw e;
        }
        log.info("【账号{}】商品配置发货完成: orderId={}, voucher={}, chat={}",
                accountId, orderId, voucherDeliveryEnabled, chatDeliveryEnabled);
        return CONSIGN_SUCCESS;
    }
}
