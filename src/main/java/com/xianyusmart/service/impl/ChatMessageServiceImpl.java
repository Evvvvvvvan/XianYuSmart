package com.xianyusmart.service.impl;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuChatMessage;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.controller.dto.MsgContextReqDTO;
import com.xianyusmart.controller.dto.MsgDTO;
import com.xianyusmart.controller.dto.MsgListReqDTO;
import com.xianyusmart.controller.dto.MsgListRespDTO;
import com.xianyusmart.service.ChatMessageService;
import com.xianyusmart.service.PlatformHistoryMessageParser;
import com.xianyusmart.service.WebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息服务实现
 * 
 * <p>职责：提供消息查询相关的服务</p>
 * <p>注意：WebSocket 消息的解析和保存现在由 SyncMessageHandler 直接处理</p>
 */
@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {
    
    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;
    
    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private WebSocketService webSocketService;

    @Autowired
    private ObjectMapper objectMapper;
    
    @Override
    public List<XianyuChatMessage> getMessagesByAccountId(Long accountId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return chatMessageMapper.findByAccountId(accountId, pageSize, offset);
    }
    
    @Override
    public List<XianyuChatMessage> getMessagesBySessionId(String sessionId) {
        return chatMessageMapper.findBySId(sessionId);
    }
    
    @Override
    public ResultObject<MsgListRespDTO> getMessageList(MsgListReqDTO reqDTO) {
        try {
            // 参数验证
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.validateFailed("xianyuAccountId不能为空");
            }
            
            // 设置默认值
            int pageNum = reqDTO.getPageNum() != null && reqDTO.getPageNum() > 0 ? reqDTO.getPageNum() : 1;
            int pageSize = reqDTO.getPageSize() != null && reqDTO.getPageSize() > 0 ? reqDTO.getPageSize() : 20;
            
            // 计算偏移量
            int offset = (pageNum - 1) * pageSize;
            
            // 获取当前账号的UNB（用于过滤）
            String currentAccountUnb = null;
            if (reqDTO.getFilterCurrentAccount() != null && reqDTO.getFilterCurrentAccount()) {
                XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
                if (account != null) {
                    currentAccountUnb = account.getUnb();
                }
            }
            
            // 查询总数
            int totalCount = chatMessageMapper.countMessages(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getXyGoodsId(),
                    currentAccountUnb
            );
            
            // 查询分页数据
            List<XianyuChatMessage> messages = chatMessageMapper.findMessagesByPage(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getXyGoodsId(),
                    currentAccountUnb,
                    pageSize,
                    offset
            );
            
            // 转换为DTO
            List<MsgDTO> msgDTOList = new ArrayList<>();
            if (messages != null) {
                for (XianyuChatMessage message : messages) {
                    MsgDTO msgDTO = new MsgDTO();
                    msgDTO.setId(message.getId());
                    msgDTO.setSId(message.getSId());
                    msgDTO.setContentType(message.getContentType());
                    msgDTO.setMsgContent(message.getMsgContent());
                    msgDTO.setXyGoodsId(message.getXyGoodsId());
                    msgDTO.setReminderUrl(message.getReminderUrl());
                    msgDTO.setSenderUserName(message.getSenderUserName());
                    msgDTO.setSenderUserId(message.getSenderUserId());
                    msgDTO.setMessageTime(message.getMessageTime());
                    msgDTOList.add(msgDTO);
                }
            }
            
            // 计算总页数
            int totalPage = (int) Math.ceil((double) totalCount / pageSize);
            if (totalPage == 0 && totalCount > 0) {
                totalPage = 1;
            }
            
            // 构建响应
            MsgListRespDTO respDTO = new MsgListRespDTO();
            respDTO.setList(msgDTOList);
            respDTO.setTotalCount(totalCount);
            respDTO.setPageNum(pageNum);
            respDTO.setPageSize(pageSize);
            respDTO.setTotalPage(totalPage);
            
            return ResultObject.success(respDTO);
            
        } catch (Exception e) {
            log.error("查询消息列表失败: accountId={}, xyGoodsId={}, filterCurrentAccount={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getFilterCurrentAccount(), e);
            return ResultObject.failed("查询消息列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<?> getContextMessages(MsgContextReqDTO reqDTO) {
        try {
            if (reqDTO.getXianyuAccountId() == null || reqDTO.getSid() == null || reqDTO.getSid().isEmpty()) {
                return ResultObject.validateFailed("xianyuAccountId和sid不能为空");
            }
            if (accountMapper.selectById(reqDTO.getXianyuAccountId()) == null) {
                return ResultObject.validateFailed("账号不存在或无权访问");
            }
            
            int limit = reqDTO.getLimit() != null && reqDTO.getLimit() > 0
                    ? Math.min(reqDTO.getLimit(), 500) : 20;
            int offset = reqDTO.getOffset() != null && reqDTO.getOffset() >= 0 ? reqDTO.getOffset() : 0;
            
            List<XianyuChatMessage> messages = chatMessageMapper.findRecentBySId(
                    reqDTO.getXianyuAccountId(), reqDTO.getSid(), limit, offset);
            
            List<MsgDTO> msgDTOList = new ArrayList<>();
            if (messages != null) {
                for (XianyuChatMessage message : messages) {
                    MsgDTO msgDTO = new MsgDTO();
                    msgDTO.setId(message.getId());
                    msgDTO.setSId(message.getSId());
                    msgDTO.setContentType(message.getContentType());
                    msgDTO.setMsgContent(message.getMsgContent());
                    msgDTO.setXyGoodsId(message.getXyGoodsId());
                    msgDTO.setReminderUrl(message.getReminderUrl());
                    msgDTO.setSenderUserName(message.getSenderUserName());
                    msgDTO.setSenderUserId(message.getSenderUserId());
                    msgDTO.setMessageTime(message.getMessageTime());
                    msgDTOList.add(msgDTO);
                }
            }
            
            return ResultObject.success(msgDTOList);
            
        } catch (Exception e) {
            log.error("查询上下文消息失败: sid={}", reqDTO.getSid(), e);
            return ResultObject.failed("查询上下文消息失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<?> syncContextMessages(MsgContextReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null || reqDTO.getSid() == null || reqDTO.getSid().isBlank()) {
            return ResultObject.validateFailed("xianyuAccountId和sid不能为空");
        }
        if (accountMapper.selectById(reqDTO.getXianyuAccountId()) == null) {
            return ResultObject.validateFailed("账号不存在或无权访问");
        }
        int maxMessages = reqDTO.getMaxMessages() == null ? 500
                : Math.max(20, Math.min(reqDTO.getMaxMessages(), 500));
        List<java.util.Map<String, Object>> history = webSocketService.listConversationHistory(
                reqDTO.getXianyuAccountId(), reqDTO.getSid(), maxMessages);
        List<XianyuChatMessage> messages = new PlatformHistoryMessageParser(objectMapper).parse(
                reqDTO.getXianyuAccountId(), reqDTO.getSid(), history);
        int saved = 0;
        for (XianyuChatMessage message : messages) {
            chatMessageMapper.insert(message);
            saved++;
        }
        return ResultObject.success(java.util.Map.of("received", history.size(), "saved", saved));
    }
}
