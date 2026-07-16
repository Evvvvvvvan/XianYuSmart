package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品分销与结算记录
 */
@Data
@TableName("merchant_distribution")
public class MerchantDistribution {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private Long supplyResourceId;

    private Long materialResourceId;

    private Long xianyuAccountId;

    private String xyGoodsId;

    private Integer status;

    private BigDecimal commissionAmount;

    private Integer settlementStatus;

    private LocalDateTime settlementTime;

    private String dataJson;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
