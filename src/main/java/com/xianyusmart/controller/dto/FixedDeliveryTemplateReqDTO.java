package com.xianyusmart.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 固定内容发货模板请求
 */
@Data
public class FixedDeliveryTemplateReqDTO {

    private Long id;

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    private String templateName;

    private String deliveryContent;

    private String messageTemplate;
}
