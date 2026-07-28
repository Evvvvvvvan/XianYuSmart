package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户通知渠道
 */
@Data
@TableName("xianyu_notification_channel")
public class XianyuNotificationChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private String channelName;

    private String webhookUrl;

    @JsonIgnore
    private String signingSecret;

    private String eventTypes;

    private Integer enabled;

    private LocalDateTime lastSuccessTime;

    private String lastErrorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
