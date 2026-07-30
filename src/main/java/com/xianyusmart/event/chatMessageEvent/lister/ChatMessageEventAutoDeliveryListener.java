package com.xianyusmart.event.chatMessageEvent.lister;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.enums.DeliveryChannel;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import com.xianyusmart.service.DeliveryTaskService;
import com.xianyusmart.service.BuyerProfileService;
import com.xianyusmart.service.NotificationCenterService;
import com.xianyusmart.service.OrderService;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.mapper.XianyuGoodsConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 自动发货事件监听器
 *
 * <p>监听 {@link ChatMessageReceivedEvent} 事件，判断是否需要触发自动发货</p>
 *
 * <p>触发条件：</p>
 * <ul>
 *   <li>contentType = 26（已付款待发货类型）</li>
 *   <li>msgContent 包含 "[已付款，待发货]" 或 "[我已付款，等待你发货]"</li>
 *   <li>小刀待刀成消息先执行免拼，小刀成功消息进入统一发货任务</li>
 * </ul>
 *
 * <p>职责：事件过滤 + 持久化订单任务，发货由统一任务调度器执行</p>
 */
@Slf4j
@Component
public class ChatMessageEventAutoDeliveryListener {

    private static final long BARGAIN_MESSAGE_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @Autowired
    private BuyerProfileService buyerProfileService;

    @Autowired
    private NotificationCenterService notificationCenterService;

    @Autowired
    private OrderService orderService;

    private final Map<String, Long> bargainMessageStages = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void handleChatMessageReceived(ChatMessageReceivedEvent event) {
        ChatMessageData message = event.getMessageData();
        Long accountId = message.getXianyuAccountId();

        log.info("【账号{}】[AutoDeliveryListener]收到事件: pnmId={}, contentType={}, xyGoodsId={}, sId={}, orderId={}",
                accountId, message.getPnmId(), message.getContentType(),
                message.getXyGoodsId(), message.getSId(), message.getOrderId());

        try {
            if (isBargainWaitingMessage(message)) {
                handleBargainWaiting(message);
                return;
            }

            boolean bargainSuccess = isBargainSuccessMessage(message);
            if (!isPaymentMessage(message) && !bargainSuccess) {
                return;
            }
            if (bargainSuccess
                    && (message.getOrderId() == null || message.getOrderId().isBlank())) {
                log.warn("【账号{}】小刀成功消息缺少订单ID: pnmId={}", accountId, message.getPnmId());
                return;
            }
            if (bargainSuccess && !claimBargainStage(message, "SUCCESS")) {
                return;
            }

            if (message.getXyGoodsId() == null || message.getSId() == null) {
                log.warn("【账号{}】消息缺少商品ID或会话ID，无法触发自动发货: pnmId={}", accountId, message.getPnmId());
                return;
            }

            String buyerUserName = message.getSenderUserName();
            log.info("【账号{}】检测到待发货消息: xyGoodsId={}, buyerUserId={}, orderId={}",
                    accountId, message.getXyGoodsId(), message.getSenderUserId(), message.getOrderId());

            Long xianyuGoodsId = resolveXianyuGoodsId(accountId, message.getXyGoodsId());
            if (xianyuGoodsId == null) {
                return;
            }

            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, message.getXyGoodsId());
            if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
                log.info("【账号{}】商品未开启自动发货，跳过: xyGoodsId={}", accountId, message.getXyGoodsId());
                return;
            }

            String blockReason = buyerProfileService.automationBlockReason(accountId, message.getSenderUserId());
            Long recordId = createOrderRecord(accountId, xianyuGoodsId, message, blockReason);
            if (recordId == null) {
                return;
            }
            if (blockReason != null) {
                log.warn("【账号{}】{}，订单已进入人工复核: orderId={}", accountId, blockReason, message.getOrderId());
            }
            notificationCenterService.dispatch("ORDER_CREATED", accountId, "发现新的待发货订单",
                    blockReason != null
                            ? "订单已暂停自动化并进入人工复核"
                            : buyerUserName == null
                            ? "新订单已进入自动发货队列"
                            : buyerUserName + " 的订单已进入自动发货队列",
                    Map.of("orderId", message.getOrderId() == null ? "" : message.getOrderId(),
                            "xyGoodsId", message.getXyGoodsId(),
                            "reviewRequired", blockReason != null));

        } catch (Exception e) {
            log.error("【账号{}】处理自动发货异常: pnmId={}", accountId, message.getPnmId(), e);
        }
    }

    private boolean isPaymentMessage(ChatMessageData message) {
        if (message.getContentType() == null || message.getContentType() != 26) {
            return false;
        }
        if (message.getMsgContent() == null) {
            return false;
        }
        return message.getMsgContent().contains("[已付款，待发货]")
                || message.getMsgContent().contains("[我已付款，等待你发货]");
    }

    private boolean isBargainWaitingMessage(ChatMessageData message) {
        String content = message.getMsgContent();
        return content != null && content.contains("小刀") && content.contains("待刀成");
    }

    private boolean isBargainSuccessMessage(ChatMessageData message) {
        String content = message.getMsgContent();
        return content != null
                && (content.contains("小刀成功") || content.contains("我已成功小刀"));
    }

    private void handleBargainWaiting(ChatMessageData message) {
        Long accountId = message.getXianyuAccountId();
        if (accountId == null
                || message.getOrderId() == null || message.getOrderId().isBlank()
                || !isNumeric(message.getXyGoodsId())
                || !isNumeric(message.getSenderUserId())) {
            log.warn("【账号{}】小刀待刀成消息字段不完整: pnmId={}", accountId, message.getPnmId());
            return;
        }

        if (resolveXianyuGoodsId(accountId, message.getXyGoodsId()) == null) {
            return;
        }

        XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(
                accountId, message.getXyGoodsId());
        if (goodsConfig == null || goodsConfig.getXianyuAutoDeliveryOn() == null
                || goodsConfig.getXianyuAutoDeliveryOn() != 1) {
            log.info("【账号{}】商品未开启自动发货，跳过小刀免拼: xyGoodsId={}",
                    accountId, message.getXyGoodsId());
            return;
        }
        if (!claimBargainStage(message, "WAITING")) {
            return;
        }

        boolean success = orderService.freeShippingBargain(
                accountId,
                message.getOrderId(),
                Long.valueOf(message.getXyGoodsId()),
                Long.valueOf(message.getSenderUserId()));
        if (success) {
            log.info("【账号{}】小刀待刀成订单免拼完成: orderId={}", accountId, message.getOrderId());
        } else {
            log.warn("【账号{}】小刀待刀成订单免拼失败: orderId={}", accountId, message.getOrderId());
        }
    }

    private boolean claimBargainStage(ChatMessageData message, String stage) {
        long now = System.currentTimeMillis();
        long expiredBefore = now - BARGAIN_MESSAGE_TTL_MS;
        bargainMessageStages.entrySet().removeIf(entry -> entry.getValue() < expiredBefore);

        String messageKey = message.getPnmId();
        if (messageKey == null || messageKey.isBlank()) {
            messageKey = String.valueOf(message.getOrderId()) + ":" + String.valueOf(message.getMsgContent());
        }
        String key = message.getXianyuAccountId() + ":" + messageKey + ":" + stage;
        Long previous = bargainMessageStages.putIfAbsent(key, now);
        if (previous == null) {
            return true;
        }
        return previous < expiredBefore && bargainMessageStages.replace(key, previous, now);
    }

    private boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private Long resolveXianyuGoodsId(Long accountId, String xyGoodsId) {
        QueryWrapper<XianyuGoodsInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("xy_good_id", xyGoodsId);
        queryWrapper.eq("xianyu_account_id", accountId);
        XianyuGoodsInfo goodsInfo = goodsInfoMapper.selectOne(queryWrapper);
        if (goodsInfo == null) {
            log.warn("【账号{}】未找到商品信息: xyGoodsId={}", accountId, xyGoodsId);
            return null;
        }
        return goodsInfo.getId();
    }

    private Long createOrderRecord(Long accountId, Long xianyuGoodsId, ChatMessageData message,
                                   String reviewReason) {
        XianyuGoodsOrder record = new XianyuGoodsOrder();
        record.setXianyuAccountId(accountId);
        record.setXianyuGoodsId(xianyuGoodsId);
        record.setXyGoodsId(message.getXyGoodsId());
        record.setPnmId(message.getPnmId());
        record.setBuyerUserId(message.getSenderUserId());
        record.setBuyerUserName(message.getSenderUserName());
        record.setSid(message.getSId());
        record.setOrderId(message.getOrderId());
        record.setContent(null);
        record.setState(reviewReason == null ? 0 : -1);
        record.setFailReason(reviewReason);
        record.setConfirmState(0);
        if (reviewReason != null) {
            record.setDeliveryStatus(com.xianyusmart.enums.DeliveryStatus.REVIEW_REQUIRED.name());
        }

        try {
            XianyuGoodsOrder task = deliveryTaskService.discover(record, DeliveryChannel.WEBSOCKET);
            log.info("【账号{}】创建订单任务成功: recordId={}, orderId={}", accountId, task.getId(), message.getOrderId());
            return task.getId();
        } catch (Exception e) {
            throw new RuntimeException("创建订单记录失败", e);
        }
    }
}
