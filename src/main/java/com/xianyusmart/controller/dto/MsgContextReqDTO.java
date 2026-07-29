package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 查询上下文消息请求DTO
 */
@Data
public class MsgContextReqDTO {

    /**
     * 闲鱼账号ID
     */
    private Long xianyuAccountId;
    
    /**
     * 会话ID
     */
    private String sid;
    
    /**
     * 限制条数（默认20）
     */
    private Integer limit;
    
    /**
     * 偏移量（默认0）
     */
    private Integer offset;

    /**
     * 单次同步历史消息上限
     */
    private Integer maxMessages;
}
