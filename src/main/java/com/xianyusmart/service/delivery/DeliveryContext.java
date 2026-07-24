package com.xianyusmart.service.delivery;

import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import lombok.Builder;
import lombok.Data;

/**
 * 发货上下文
 *
 * <p>封装发货过程中需要的所有信息，供 {@link DeliveryContentStrategy} 使用。</p>
 */
@Data
@Builder
public class DeliveryContext {
    private Long recordId;
    private Long accountId;
    private String xyGoodsId;
    private String sId;
    private String orderId;
    private String buyerUserName;
    private Integer quantity;
    private XianyuGoodsAutoDeliveryConfig deliveryConfig;
    private XianyuFixedDeliveryTemplate fixedTemplate;
}
