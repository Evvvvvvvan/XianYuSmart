package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 手动评价请求
 */
@Data
public class ManualRateReqDTO {

    private Long xianyuAccountId;

    private String orderId;

    private String content;
}
