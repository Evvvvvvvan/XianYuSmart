package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryChannel;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PendingOrderPollService {

    @Autowired
    private OrderService orderService;

    @Autowired
    private XianyuGoodsOrderMapper orderMapper;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @SuppressWarnings("unchecked")
    public int deliverPendingOrders(Long accountId) {
        List<Map<String, Object>> pendingOrders = orderService.queryPendingOrders(accountId);
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            return 0;
        }
        int queuedCount = 0;
        for (Map<String, Object> order : pendingOrders) {
            try {
                Object commonDataObj = order.get("commonData");
                if (!(commonDataObj instanceof Map)) continue;
                Map<String, Object> commonData = (Map<String, Object>) commonDataObj;
                String orderId = (String) commonData.get("orderId");
                String orderStatus = (String) commonData.get("orderStatus");
                if (orderId == null || !"待发货".equals(orderStatus)) continue;
                XianyuGoodsOrder task = queueOrder(accountId, order);
                if (task != null) queuedCount++;
            } catch (Exception e) {
                log.warn("【账号{}】处理待发货订单异常: {}", accountId, e.getMessage());
            }
        }
        return queuedCount;
    }

    @SuppressWarnings("unchecked")
    public void syncOrdersToDb(Long accountId, List<Map<String, Object>> pendingOrders) {
        for (Map<String, Object> order : pendingOrders) {
            try {
                Object commonDataObj = order.get("commonData");
                if (!(commonDataObj instanceof Map)) continue;
                Map<String, Object> commonData = (Map<String, Object>) commonDataObj;

                String orderId = (String) commonData.get("orderId");
                if (orderId == null) continue;

                XianyuGoodsOrder existing = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
                if (existing != null) {
                    enrichFromDetailApi(accountId, orderId, existing);
                    continue;
                }

                XianyuGoodsOrder record = buildOrderRecord(accountId, order);
                String itemId = (String) commonData.get("itemId");
                if (isAutoDeliveryEnabled(accountId, itemId)) {
                    deliveryTaskService.discover(record, DeliveryChannel.HTTP_API);
                } else {
                    record.setDeliveryStatus(DeliveryStatus.SKIPPED.name());
                    record.setDeliveryChannel(DeliveryChannel.HTTP_API.name());
                    orderMapper.insert(record);
                }
                log.info("【账号{}】同步新订单到DB: orderId={}", accountId, orderId);

                enrichFromDetailApi(accountId, orderId, null);
            } catch (Exception e) {
                log.warn("【账号{}】同步订单异常: {}", accountId, e.getMessage());
            }
        }
    }

    private XianyuGoodsOrder queueOrder(Long accountId, Map<String, Object> order) {
        XianyuGoodsOrder record = buildOrderRecord(accountId, order);
        if (!isAutoDeliveryEnabled(accountId, record.getXyGoodsId())) {
            return null;
        }
        XianyuGoodsOrder existing = orderMapper.selectByAccountIdAndOrderId(accountId, record.getOrderId());
        if (existing != null) {
            if (!Integer.valueOf(1).equals(existing.getState())) {
                deliveryTaskService.requeue(existing.getId());
            }
            return existing;
        }
        return deliveryTaskService.discover(record, DeliveryChannel.HTTP_API);
    }

    private boolean isAutoDeliveryEnabled(Long accountId, String xyGoodsId) {
        if (xyGoodsId == null) {
            return false;
        }
        var config = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
        return config != null && Integer.valueOf(1).equals(config.getXianyuAutoDeliveryOn());
    }

    @SuppressWarnings("unchecked")
    private XianyuGoodsOrder buildOrderRecord(Long accountId, Map<String, Object> order) {
        Object commonDataObj = order.get("commonData");
        Map<String, Object> commonData = (Map<String, Object>) commonDataObj;

        String orderId = (String) commonData.get("orderId");
        String itemId = (String) commonData.get("itemId");
        String orderStatus = (String) commonData.get("orderStatus");

        String buyerNick = null;
        String buyerUserId = null;
        Object buyerInfoObj = order.get("buyerInfoVO");
        if (buyerInfoObj instanceof Map) {
            Map<String, Object> buyerInfo = (Map<String, Object>) buyerInfoObj;
            buyerNick = (String) buyerInfo.get("userNick");
            buyerUserId = (String) buyerInfo.get("userId");
        }

        String goodsTitle = null;
        Object itemVOObj = order.get("itemVO");
        if (itemVOObj instanceof Map) {
            Map<String, Object> itemVO = (Map<String, Object>) itemVOObj;
            goodsTitle = (String) itemVO.get("title");
        }

        String totalPrice = null;
        Integer buyNum = null;
        Object priceVOObj = order.get("priceVO");
        if (priceVOObj instanceof Map) {
            Map<String, Object> priceVO = (Map<String, Object>) priceVOObj;
            Object tp = priceVO.get("totalPrice");
            if (tp instanceof String) totalPrice = (String) tp;
            Object bn = priceVO.get("buyNum");
            if (bn instanceof String) {
                try { buyNum = Integer.parseInt((String) bn); } catch (Exception e) { buyNum = 1; }
            } else if (bn instanceof Number) {
                buyNum = ((Number) bn).intValue();
            }
        }

        XianyuGoodsOrder record = new XianyuGoodsOrder();
        record.setXianyuAccountId(accountId);
        record.setOrderId(orderId);
        record.setXyGoodsId(itemId);
        record.setPnmId("api_" + orderId);
        record.setBuyerUserId(buyerUserId);
        record.setBuyerUserName(buyerNick);
        record.setGoodsTitle(goodsTitle);
        record.setTotalPrice(totalPrice);
        record.setBuyNum(buyNum);
        record.setState("待发货".equals(orderStatus) ? 0 : 1);
        record.setConfirmState(0);

        String createTime = (String) commonData.get("createTime");
        String payTime = (String) commonData.get("paySuccessTime");
        if (createTime != null) record.setOrderCreateTime(createTime);
        if (payTime != null) record.setPaySuccessTime(payTime);

        return record;
    }

    @SuppressWarnings("unchecked")
    private void enrichFromDetailApi(Long accountId, String orderId, XianyuGoodsOrder existing) {
        try {
            Map<String, Object> detailMap = orderService.getOrderDetailMap(accountId, orderId);
            if (detailMap == null) return;

            Object moduleObj = detailMap.get("module");
            if (!(moduleObj instanceof Map)) return;
            Map<String, Object> module = (Map<String, Object>) moduleObj;

            String buyerUserName = null;
            String buyerUserId = null;
            Object merchantBuyerVO = module.get("merchantBuyerVO");
            if (merchantBuyerVO instanceof Map) {
                Map<String, Object> buyer = (Map<String, Object>) merchantBuyerVO;
                Object userNick = buyer.get("userNick");
                if (userNick instanceof String) buyerUserName = (String) userNick;
                Object uid = buyer.get("userId");
                if (uid instanceof String) buyerUserId = (String) uid;
            }

            String orderCreateTime = null;
            String paySuccessTime = null;
            String consignTime = null;
            Object merchantCommonData = module.get("merchantCommonData");
            if (merchantCommonData instanceof Map) {
                Map<String, Object> cd = (Map<String, Object>) merchantCommonData;
                Object ct = cd.get("createTime");
                if (ct instanceof String) orderCreateTime = (String) ct;
                Object pt = cd.get("paySuccessTime");
                if (pt instanceof String) paySuccessTime = (String) pt;
                Object ct2 = cd.get("consignTime");
                if (ct2 instanceof String) consignTime = (String) ct2;
            }

            String goodsTitle = null;
            String skuName = null;
            Object merchantItemVO = module.get("merchantItemVO");
            if (merchantItemVO instanceof Map) {
                Map<String, Object> merchantItem = (Map<String, Object>) merchantItemVO;
                Object title = merchantItem.get("title");
                if (title instanceof String) goodsTitle = (String) title;
                Object sku = merchantItem.get("skuText");
                if (sku instanceof String) skuName = (String) sku;
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

            if (existing == null) {
                existing = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
            }
            if (existing == null) return;

            orderMapper.updateOrderDetail(existing.getId(), buyerUserName, orderCreateTime, paySuccessTime, consignTime, skuName, null, goodsTitle, totalPrice, buyNum);
            log.info("【账号{}】从详情API补充订单字段: orderId={}", accountId, orderId);
        } catch (Exception e) {
            log.warn("【账号{}】补充订单详情异常: orderId={}", accountId, orderId, e);
        }
    }
}
