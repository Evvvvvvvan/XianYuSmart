package com.xianyusmart.event.chatMessageEvent.lister;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.BuyerProfileService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 买家最近互动资料监听器
 */
@Component
public class ChatMessageBuyerProfileListener {

    private final BuyerProfileService buyerProfileService;
    private final AccountService accountService;

    public ChatMessageBuyerProfileListener(BuyerProfileService buyerProfileService,
                                           AccountService accountService) {
        this.buyerProfileService = buyerProfileService;
        this.accountService = accountService;
    }

    @Order(1)
    @Async
    @EventListener
    public void handleChatMessageReceived(ChatMessageReceivedEvent event) {
        ChatMessageData message = event.getMessageData();
        String ownUserId = accountService.getXianyuUserId(message.getXianyuAccountId());
        if (message.getSenderUserId() != null && !message.getSenderUserId().equals(ownUserId)) {
            buyerProfileService.touch(message.getXianyuAccountId(), message.getSenderUserId(),
                    message.getSenderUserName(), message.getMessageTime());
        }
    }
}
