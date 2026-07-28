package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部卡密请求幂等记录
 */
@Data
@TableName("xianyu_kami_external_request")
public class XianyuKamiExternalRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private Long kamiConfigId;

    private Long xianyuAccountId;

    private String orderId;

    private String requestToken;

    private Integer quantity;

    private String requestStatus;

    private Integer attemptCount;

    private String responseExcerpt;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
