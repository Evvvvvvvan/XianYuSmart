package com.xianyusmart.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 买家关联商品汇总
 */
@Data
public class BuyerRelatedGoodsDTO {

    private String xyGoodsId;

    private String title;

    private String coverPic;

    private String soldPrice;

    private Long orderCount;

    private BigDecimal totalAmount;

    private LocalDateTime lastOrderTime;
}
