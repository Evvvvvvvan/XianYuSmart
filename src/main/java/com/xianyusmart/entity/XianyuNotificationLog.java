package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知发送记录
 */
@Data
@TableName("xianyu_notification_log")
public class XianyuNotificationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private Long channelId;

    private String eventType;

    private Long xianyuAccountId;

    private String title;

    private Integer sendStatus;

    private Integer httpStatus;

    private String errorMessage;

    private LocalDateTime createTime;
}
