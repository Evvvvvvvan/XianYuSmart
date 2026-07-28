package com.xianyusmart.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家资料展示内容
 */
@Data
public class BuyerProfileRespDTO {

    private Long id;

    private Long xianyuAccountId;

    private String buyerUserId;

    private String buyerUserName;

    @JsonIgnore
    private String tagsJson;

    private List<String> tags;

    private String note;

    private Boolean automationBlocked;

    private String blockedReason;

    private LocalDateTime lastInteractionTime;

    private Long messageCount;

    private Long orderCount;

    private BigDecimal totalAmount;
}
