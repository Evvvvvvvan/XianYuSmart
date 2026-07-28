package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知渠道展示内容
 */
@Data
public class NotificationChannelRespDTO {

    private Long id;

    private String channelName;

    private String webhookUrl;

    private Boolean secretConfigured;

    private List<String> eventTypes;

    private Boolean enabled;

    private LocalDateTime lastSuccessTime;

    private String lastErrorMessage;

    private LocalDateTime updateTime;
}
