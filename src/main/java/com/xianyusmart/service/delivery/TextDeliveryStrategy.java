package com.xianyusmart.service.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 固定内容发货策略（deliveryMode 包含固定内容标记）
 *
 * <p>直接返回配置的文本内容 {@link com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig#getAutoDeliveryContent()}</p>
 */
@Slf4j
@Component
@Order(1)
public class TextDeliveryStrategy implements DeliveryContentStrategy {

    @Override
    public boolean supports(int deliveryMode) {
        return (deliveryMode & 1) == 1;
    }

    @Override
    public String resolve(DeliveryContext context) {
        String content = context.getDeliveryConfig().getAutoDeliveryContent();
        if (content == null || content.isEmpty()) {
            log.warn("【账号{}】固定内容发货模式下未配置发货内容: xyGoodsId={}", context.getAccountId(), context.getXyGoodsId());
            return null;
        }
        log.info("【账号{}】固定内容发货模式", context.getAccountId());
        return content;
    }
}
