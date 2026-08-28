package com.xianyusmart.event;

import com.xianyusmart.entity.XianyuGoodsOrder;

/**
 * 发货私聊实际发送成功事件
 */
public record DeliveryMessageSentEvent(XianyuGoodsOrder order) {
}
