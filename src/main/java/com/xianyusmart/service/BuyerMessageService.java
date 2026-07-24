package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 买家履约消息服务
 */
@Slf4j
@Service
public class BuyerMessageService {

    public static final String DEFAULT_DELIVERY_MESSAGE_TEMPLATE = "{deliveryContent}";
    public static final int DEFAULT_RECEIPT_FOLLOW_UP_INTERVAL_SECONDS = 5;
    private static final int MAX_RECEIPT_FOLLOW_UP_MESSAGES = 5;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final WebSocketService webSocketService;
    private final SentMessageSaveService sentMessageSaveService;
    private final XianyuGoodsOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public BuyerMessageService(WebSocketService webSocketService,
                               SentMessageSaveService sentMessageSaveService,
                               XianyuGoodsOrderMapper orderMapper,
                               ObjectMapper objectMapper) {
        this.webSocketService = webSocketService;
        this.sentMessageSaveService = sentMessageSaveService;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用同一份实际发货内容渲染私聊文案，避免卡密重复分配。
     */
    public String renderDeliveryMessage(XianyuGoodsAutoDeliveryConfig config, String buyerName,
                                        String orderId, String deliveryContent) {
        String template = normalizeDeliveryMessageTemplate(
                config == null ? null : config.getDeliveryMessageTemplate());
        return renderVariables(template, buyerName, orderId, deliveryContent);
    }

    public String renderVariables(String template, String buyerName, String orderId, String deliveryContent) {
        String normalized = template == null ? "" : template;
        return normalized
                .replace("{buyerName}", safeValue(buyerName))
                .replace("{orderId}", safeValue(orderId))
                .replace("{deliveryContent}", safeValue(deliveryContent));
    }

    public String normalizeDeliveryMessageTemplate(String template) {
        String normalized = template == null ? "" : template.trim();
        if (normalized.isEmpty()) {
            return DEFAULT_DELIVERY_MESSAGE_TEMPLATE;
        }
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("发货私聊模板不能超过1000个字符");
        }
        return normalized;
    }

    public List<String> parseReceiptFollowUpMessages(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> source = objectMapper.readValue(messagesJson, new TypeReference<>() {});
            List<String> result = new ArrayList<>();
            for (String message : source) {
                String normalized = message == null ? "" : message.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                if (normalized.length() > MAX_MESSAGE_LENGTH) {
                    throw new IllegalArgumentException("单条收货后话术不能超过500个字符");
                }
                result.add(normalized);
                if (result.size() > MAX_RECEIPT_FOLLOW_UP_MESSAGES) {
                    throw new IllegalArgumentException("收货后话术最多添加5条");
                }
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("收货后话术格式不正确");
        }
    }

    public String normalizeReceiptFollowUpMessages(String messagesJson) {
        try {
            return objectMapper.writeValueAsString(parseReceiptFollowUpMessages(messagesJson));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("收货后话术格式不正确");
        }
    }

    public int normalizeReceiptFollowUpInterval(Integer intervalSeconds) {
        int interval = intervalSeconds == null ? DEFAULT_RECEIPT_FOLLOW_UP_INTERVAL_SECONDS : intervalSeconds;
        if (interval < 5 || interval > 600) {
            throw new IllegalArgumentException("收货后话术间隔应为5至600秒");
        }
        return interval;
    }

    /**
     * 凭证成功后先持久化私聊内容，再立即发送；失败内容由定时任务继续重试。
     */
    public boolean queueDeliveryMessage(XianyuGoodsOrder order, XianyuGoodsAutoDeliveryConfig config,
                                        String actualDeliveryContent) {
        if (order == null || order.getId() == null) {
            return false;
        }
        String message = renderDeliveryMessage(config, order.getBuyerUserName(), order.getOrderId(),
                actualDeliveryContent);
        orderMapper.prepareDeliveryMessage(order.getId(), message);
        order.setDeliveryMessageContent(message);
        order.setDeliveryMessageState(0);
        order.setDeliveryMessageAttemptCount(0);
        return sendPreparedDeliveryMessage(order);
    }

    public void retryPendingDeliveryMessages() {
        for (XianyuGoodsOrder order : orderMapper.selectDueDeliveryMessages(100)) {
            try {
                TenantContext.set(order.getTenantId());
                sendPreparedDeliveryMessage(order);
            } catch (Exception e) {
                log.warn("【账号{}】重试发货私聊异常: orderId={}, error={}",
                        order.getXianyuAccountId(), order.getOrderId(), e.getMessage());
                deferDeliveryMessage(order);
            } finally {
                TenantContext.clear();
            }
        }
    }

    public boolean sendReceiptFollowUp(XianyuGoodsOrder order, String messageTemplate) {
        String message = renderVariables(messageTemplate, order.getBuyerUserName(), order.getOrderId(),
                order.getContent());
        return sendMessage(order, message);
    }

    private boolean sendPreparedDeliveryMessage(XianyuGoodsOrder order) {
        String message = order.getDeliveryMessageContent();
        if (message == null || message.isBlank()) {
            return false;
        }
        // 数据库抢占保证同一条待发私聊在多实例下只有一个发送者。
        if (orderMapper.claimDeliveryMessage(order.getId()) != 1) {
            return false;
        }
        boolean success = sendMessage(order, message);
        if (success) {
            orderMapper.markDeliveryMessageSent(order.getId());
            return true;
        }
        deferDeliveryMessage(order);
        return false;
    }

    private boolean sendMessage(XianyuGoodsOrder order, String message) {
        String recipientId = resolveRecipientId(order);
        if (recipientId == null || !webSocketService.isConnected(order.getXianyuAccountId())) {
            return false;
        }
        boolean success = webSocketService.sendMessageWithResult(
                order.getXianyuAccountId(), recipientId, recipientId, message);
        if (success) {
            sentMessageSaveService.saveAiAssistantReply(
                    order.getXianyuAccountId(), recipientId, recipientId, message, order.getXyGoodsId());
        }
        return success;
    }

    private void deferDeliveryMessage(XianyuGoodsOrder order) {
        int attempts = order.getDeliveryMessageAttemptCount() == null ? 0 : order.getDeliveryMessageAttemptCount();
        long retrySeconds = Math.min(300L, 15L << Math.min(attempts, 4));
        orderMapper.deferDeliveryMessage(order.getId(), LocalDateTime.now().plusSeconds(retrySeconds));
    }

    private String resolveRecipientId(XianyuGoodsOrder order) {
        String recipientId = order.getSid();
        if (recipientId == null || recipientId.isBlank()) {
            recipientId = order.getBuyerUserId();
        }
        if (recipientId == null || recipientId.isBlank()) {
            return null;
        }
        return recipientId.replace("@goofish", "");
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }
}
