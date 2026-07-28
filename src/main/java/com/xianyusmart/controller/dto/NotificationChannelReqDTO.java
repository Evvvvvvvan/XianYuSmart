package com.xianyusmart.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 通知渠道保存参数
 */
@Data
public class NotificationChannelReqDTO {

    private Long id;

    @NotBlank(message = "渠道名称不能为空")
    private String channelName;

    private String channelType;

    private String webhookUrl;

    private String signingSecret;

    private Map<String, String> config;

    private String messageTemplate;

    private List<String> eventTypes;

    private Boolean enabled;
}
