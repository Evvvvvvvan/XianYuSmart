package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自动发货记录DTO (简化版)
 */
@Data
public class AutoDeliveryRecordDTO {
    
    /**
     * 主键ID
     */
    private Long id;
    
    private Long xianyuAccountId;
    
    private String xyGoodsId;
    
    /**
     * 商品标题
     */
    private String goodsTitle;
    
    /**
     * 买家用户名称
     */
    private String buyerUserName;
    
    /**
     * 发货消息内容
     */
    private String content;
    
    /**
     * 发货是否成功: 1-成功, 0-失败
     */
    private Integer state;

    /**
     * 履约任务状态
     */
    private String deliveryStatus;

    /**
     * 履约失败原因
     */
    private String failReason;
    
    /**
     * 确认发货状态: 0-未确认, 1-已确认
     */
    private Integer confirmState;

    private Integer rateStatus;

    private LocalDateTime rateTime;

    private String rateContent;

    private String rateSource;
    
    /**
     * 订单ID
     */
    private String orderId;

    private String skuName;

    private String orderCreateTime;

    private String paySuccessTime;

    private String consignTime;

    private String totalPrice;

    private Integer buyNum;

    private String createTime;
}
