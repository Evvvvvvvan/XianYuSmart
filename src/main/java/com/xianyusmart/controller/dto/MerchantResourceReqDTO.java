package com.xianyusmart.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 商家运营资源保存请求
 */
@Data
public class MerchantResourceReqDTO {

    private Long id;

    private String resourceType;

    private String name;

    private Integer status;

    private Long xianyuAccountId;

    private String xyGoodsId;

    private Integer stock;

    private BigDecimal amount;

    private LocalDateTime scheduledTime;

    private Map<String, Object> data;
}
