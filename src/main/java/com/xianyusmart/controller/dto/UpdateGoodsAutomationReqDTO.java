package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 商品运营自动化开关请求
 */
@Data
public class UpdateGoodsAutomationReqDTO {

    private Long xianyuAccountId;

    private String xyGoodsId;

    private List<String> xyGoodsIds;

    private Integer xianyuAutoRateOn;

    private String xianyuAutoRateContent;

    private Integer xianyuAutoPolishOn;
}
