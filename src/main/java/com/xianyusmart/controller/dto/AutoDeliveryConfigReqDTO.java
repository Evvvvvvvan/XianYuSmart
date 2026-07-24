package com.xianyusmart.controller.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 自动发货配置请求DTO
 */
@Data
public class AutoDeliveryConfigReqDTO {
    
    /**
     * 闲鱼账号ID（必选）
     */
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;
    
    /**
     * 本地闲鱼商品ID
     */
    private Long xianyuGoodsId;
    
    /**
     * 闲鱼的商品ID（必选）
     */
    @NotNull(message = "闲鱼商品ID不能为空")
    private String xyGoodsId;
    
    /**
     * 发货类型：1-固定内容，2-卡密
     */
    private Integer deliveryMode = 1;

    private Long fixedTemplateId;

    private String skuId;

    private String skuName;

    private String autoDeliveryContent;

    /**
     * 卡密发货：绑定的卡密配置ID列表（逗号分隔）
     */
    private String kamiConfigIds;

    /**
     * 卡密发货文案模板，使用{kmKey}占位符替换卡密内容
     */
    private String kamiDeliveryTemplate;

    /**
     * 发货私聊模板，支持{buyerName}、{orderId}、{deliveryContent}
     */
    private String deliveryMessageTemplate;

    private Integer voucherDeliveryEnabled = 1;

    private Integer chatDeliveryEnabled = 1;

    /**
     * 买家确认收货后按顺序发送的话术列表JSON
     */
    private String receiptFollowUpMessages;

    /**
     * 收货后话术发送间隔秒数
     */
    private Integer receiptFollowUpIntervalSeconds;

    /**
     * 自动发货图片URL
     */
    private String autoDeliveryImageUrl;
    
    /**
     * 自动确认发货开关：0-关闭，1-开启
     */
    private Integer autoConfirmShipment;
}
