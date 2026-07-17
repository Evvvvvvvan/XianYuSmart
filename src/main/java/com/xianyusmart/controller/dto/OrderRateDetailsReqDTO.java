package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量查询订单评价请求
 */
@Data
public class OrderRateDetailsReqDTO {

    private Long xianyuAccountId;

    private List<String> orderIds;
}
