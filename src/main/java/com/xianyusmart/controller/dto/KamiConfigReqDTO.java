package com.xianyusmart.controller.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class KamiConfigReqDTO {

    private Long id;

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    private String aliasName;

    private String sourceType;

    private String externalApiUrl;

    private String externalApiHeaders;

    private String externalApiBody;

    private String externalApiResultPath;

    private Integer externalApiTimeoutSeconds;

    private Integer alertEnabled;

    private Integer alertThresholdType;

    private Integer alertThresholdValue;

    private String alertEmail;
}
