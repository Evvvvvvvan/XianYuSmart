package com.xianyusmart.service;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;

import java.util.List;

/**
 * 自动回复服务接口
 * 
 * @date 2026/4/22
 */
public interface AutoReplyService {
    
    /**
     * 执行自动回复（单条消息）
     * 
     * @param messageData 消息数据
     */
    void executeAutoReply(ChatMessageData messageData);
    
    /**
     * 执行自动回复（多条消息，延时期间收集）
     * 
     * @param messageList 触发消息列表
     */
    void executeAutoReply(List<ChatMessageData> messageList);

    void executeAutoReply(List<ChatMessageData> messageList, Long recordId);

    /**
     * 执行自动回复并把状态迁移限制在租约持有者上。
     *
     * @param leaseOwner 领取该记录时写入的 workerId，为 null 时只做状态守卫
     */
    void executeAutoReply(List<ChatMessageData> messageList, Long recordId, String leaseOwner);
    
    /**
     * 检查商品是否开启自动回复
     * 
     * @param accountId 账号ID
     * @param xyGoodsId 商品ID
     * @return 是否开启
     */
    boolean isAutoReplyEnabled(Long accountId, String xyGoodsId);
}
