package com.xianyusmart.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家关联会话消息
 */
@Data
public class BuyerMessageDTO {

    private Long id;

    private String pnmId;

    private String sid;

    private Integer contentType;

    private String content;

    private String senderUserName;

    private String senderUserId;

    private String xyGoodsId;

    private Long messageTime;

    private LocalDateTime createTime;

    private String direction;

    private List<String> relatedOrderIds;
}
