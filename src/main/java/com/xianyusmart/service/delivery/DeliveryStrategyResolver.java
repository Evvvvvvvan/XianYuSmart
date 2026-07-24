package com.xianyusmart.service.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 发货内容策略解析器
 *
 * <p>根据 deliveryMode 执行选中的内容策略并组合实际发货内容。</p>
 * <p>策略列表由Spring自动注入所有 {@link DeliveryContentStrategy} 实现。</p>
 */
@Slf4j
@Component
public class DeliveryStrategyResolver {

    @Autowired
    private List<DeliveryContentStrategy> strategies;

    /**
     * 根据发货模式解析发货内容
     *
     * @param deliveryMode 发货模式（1=固定内容，2=卡密，3=固定内容+卡密）
     * @param context      发货上下文
     * @return 发货内容文本，null表示无法发货
     */
    public String resolve(int deliveryMode, DeliveryContext context) {
        List<String> contents = new ArrayList<>();
        for (DeliveryContentStrategy strategy : strategies) {
            if (strategy.supports(deliveryMode)) {
                String content = strategy.resolve(context);
                if (content == null || content.isBlank()) {
                    return null;
                }
                contents.add(content);
            }
        }
        if (!contents.isEmpty()) {
            return String.join("\n", contents);
        }
        log.warn("【账号{}】未知的发货模式: deliveryMode={}", context.getAccountId(), deliveryMode);
        return null;
    }
}
