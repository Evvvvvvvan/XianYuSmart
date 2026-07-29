package com.xianyusmart.service;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.XianyuChatMessage;
import com.xianyusmart.controller.dto.MsgContextReqDTO;
import com.xianyusmart.controller.dto.MsgListReqDTO;
import com.xianyusmart.controller.dto.MsgListRespDTO;
import java.util.List;

/**
 * 聊天消息服务接口
 * 
 * <p>职责：提供消息查询相关的服务</p>
 * <p>注意：WebSocket 消息的解析和保存现在由 SyncMessageHandler 直接处理</p>
 */
public interface ChatMessageService {
    
    /**
     * 查询账号的聊天消息
     * 
     * @param accountId 账号ID
     * @param page 页码（从1开始）
     * @param pageSize 每页大小
     * @return 消息列表
     */
    List<XianyuChatMessage> getMessagesByAccountId(Long accountId, int page, int pageSize);
    
    /**
     * 查询会话的聊天消息
     * 
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<XianyuChatMessage> getMessagesBySessionId(String sessionId);
    
    /**
     * 分页查询消息列表
     * 
     * @param reqDTO 查询请求参数
     * @return 消息列表响应
     */
    ResultObject<MsgListRespDTO> getMessageList(MsgListReqDTO reqDTO);
    
    /**
     * 根据会话ID获取上下文消息（最近N条）
     * 
     * @param reqDTO 查询请求参数（sid, limit）
     * @return 消息列表
     */
    ResultObject<?> getContextMessages(MsgContextReqDTO reqDTO);

    /**
     * 从平台同步指定会话历史消息
     */
    ResultObject<?> syncContextMessages(MsgContextReqDTO reqDTO);
}
