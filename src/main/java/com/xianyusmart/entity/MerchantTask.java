package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家运营执行任务
 */
@Data
@TableName("merchant_task")
public class MerchantTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private String taskType;

    private Long resourceId;

    private Long xianyuAccountId;

    private String xyGoodsId;

    private Integer status;

    private LocalDateTime scheduledTime;

    private Integer attemptCount;

    private Integer maxAttempts;

    private LocalDateTime nextRetryTime;

    private String requestJson;

    private String resultJson;

    private String errorMessage;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
