package com.xianyusmart.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 买家资料保存参数
 */
@Data
public class BuyerProfileSaveReqDTO {

    private Long id;

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "买家用户ID不能为空")
    private String buyerUserId;

    private String buyerUserName;

    private List<String> tags;

    private String note;

    private Boolean automationBlocked;

    private String blockedReason;
}
