package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 商家运营任务创建请求
 */
@Data
public class MerchantTaskReqDTO {

    private String taskType;

    private Long resourceId;

    private Long xianyuAccountId;

    private String xyGoodsId;

    private LocalDateTime scheduledTime;

    private Map<String, Object> request;
}
