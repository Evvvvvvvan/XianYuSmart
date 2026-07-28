package com.xianyusmart.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 买家详情查询条件
 */
@Data
public class BuyerProfileDetailReqDTO {

    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "买家ID不能为空")
    private String buyerUserId;
}
