package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 买家资料查询条件
 */
@Data
public class BuyerProfileQueryReqDTO {

    private Long xianyuAccountId;

    private String keyword;

    private Boolean automationBlocked;

    private Integer pageNum;

    private Integer pageSize;
}
