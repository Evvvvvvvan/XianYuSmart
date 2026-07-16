package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 商品运营自动化开关请求
 */
@Data
public class UpdateGoodsAutomationReqDTO {

    private Long xianyuAccountId;

    private String xyGoodsId;

    private Integer xianyuAutoRateOn;

    private String xianyuAutoRateContent;

    private Integer xianyuAutoPolishOn;
}
