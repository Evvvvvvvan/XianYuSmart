package com.xianyusmart.service;

import java.util.List;
import java.util.Map;

/**
 * 订单服务接口
 */
public interface OrderService {

    String CONSIGN_SUCCESS = "虚拟发货成功";
    String CONSIGN_UNCERTAIN = "虚拟发货结果待确认";
    String CONSIGN_ALREADY_DELIVERED = "订单已存在发货凭证";
    
    /**
     * 确认发货
     * 
     * @param accountId 账号ID
     * @param orderId 订单ID
     * @return 操作结果
     */
    String confirmShipment(Long accountId, String orderId);
    
    /**
     * 调用闲鱼API确认发货
     * 
     * @param accountId 账号ID
     * @param orderId 订单ID
     * @return 操作结果
     */
    String confirmShipmentToXianyu(Long accountId, String orderId);

    String consignDummyDelivery(Long accountId, String orderId, String tradeText, List<String> imageUrls);

    String consignDummyDeliveryWithConfig(Long accountId, String xyGoodsId, String orderId);

    /**
     * 获取订单详情
     *
     * @param accountId 账号ID
     * @param orderId 订单ID
     * @return 订单详情JSON
     */
    String getOrderDetail(Long accountId, String orderId);

    String getOrderDetailFromLocal(Long accountId, String orderId);

    List<Map<String, Object>> queryPendingOrders(Long accountId);

    Map<String, Object> getOrderDetailMap(Long accountId, String orderId);
}
