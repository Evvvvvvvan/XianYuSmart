package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通知渠道展示内容
 */
@Data
public class NotificationChannelRespDTO {

    private Long id;

    private String channelName;

    private String channelType;

    private String webhookUrl;

    private Boolean secretConfigured;

    private Map<String, String> config;

    private String messageTemplate;

    private List<String> eventTypes;

    private Boolean enabled;

    private LocalDateTime lastSuccessTime;

    private String lastErrorMessage;

    private LocalDateTime updateTime;
}
