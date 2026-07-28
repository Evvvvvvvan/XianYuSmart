package com.xianyusmart.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 通知渠道保存参数
 */
@Data
public class NotificationChannelReqDTO {

    private Long id;

    @NotBlank(message = "渠道名称不能为空")
    private String channelName;

    @NotBlank(message = "Webhook 地址不能为空")
    private String webhookUrl;

    private String signingSecret;

    private List<String> eventTypes;

    private Boolean enabled;
}
