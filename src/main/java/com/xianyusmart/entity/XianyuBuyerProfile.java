package com.xianyusmart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家关系资料
 */
@Data
@TableName("xianyu_buyer_profile")
public class XianyuBuyerProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long tenantId;

    private Long xianyuAccountId;

    private String buyerUserId;

    private String buyerUserName;

    private String tagsJson;

    private String note;

    private Integer automationBlocked;

    private String blockedReason;

    private LocalDateTime lastInteractionTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
