package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家运营资源
 */
@Data
@TableName("merchant_resource")
public class MerchantResource {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private String resourceType;

    private String name;

    private Integer status;

    private Long xianyuAccountId;

    private String xyGoodsId;

    private Integer stock;

    private BigDecimal amount;

    private LocalDateTime scheduledTime;

    private LocalDateTime lastRunTime;

    private String dataJson;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
