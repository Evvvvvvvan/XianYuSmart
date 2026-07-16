package com.xianyusmart.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 商品分销记录保存请求
 */
@Data
public class MerchantDistributionReqDTO {

    private Long supplyResourceId;

    private Long materialResourceId;

    private Long xianyuAccountId;

    private String xyGoodsId;

    private Integer status;

    private BigDecimal commissionAmount;

    private Map<String, Object> data;
}
