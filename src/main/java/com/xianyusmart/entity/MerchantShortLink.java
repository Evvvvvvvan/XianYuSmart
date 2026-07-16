package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户短链
 */
@Data
@TableName("merchant_short_link")
public class MerchantShortLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private String token;

    private String targetUrl;

    private Long clickCount;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
