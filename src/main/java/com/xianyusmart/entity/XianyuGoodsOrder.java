package com.xianyusmart.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 商品订单实体类
 */
@Data
public class XianyuGoodsOrder {
    
    private Long id;

    @JsonIgnore
    private Long tenantId;
    
    private Long xianyuAccountId;
    
    private Long xianyuGoodsId;
    
    private String xyGoodsId;
    
    private String pnmId;
    
    private String orderId;
    
    private String buyerUserId;
    
    private String buyerUserName;
    
    private String sid;
    
    private String content;
    
    private Integer state;
    
    private String failReason;
    
    private Integer confirmState;

    private Integer rateStatus;

    private LocalDateTime rateTime;

    private String rateContent;

    private String rateSource;

    private String deliveryMessageContent;

    private Integer deliveryMessageState;

    private Integer deliveryMessageAttemptCount;

    private LocalDateTime deliveryMessageNextRetryTime;

    private Integer buyerConfirmedReceipt;

    private Integer receiptFollowUpSentCount;

    private LocalDateTime receiptFollowUpNextTime;

    private Integer receiptFollowUpCompleted;
    
    private String createTime;
    
    private String goodsTitle;

    private String skuName;

    private String skuId;

    private String orderCreateTime;

    private String paySuccessTime;

    private String consignTime;

    private String totalPrice;

    private Integer buyNum;

    private String deliveryStatus;

    private Integer expectedQuantity;

    private Integer deliveredQuantity;

    private Integer attemptCount;

    private LocalDateTime nextRetryTime;

    private String leaseOwner;

    private LocalDateTime leaseExpireTime;

    private String deliveryChannel;

    private String lastErrorCode;

    private String lastErrorMessage;
}
