package com.xianyusmart.service.delivery;

import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.service.FixedDeliveryTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 固定内容发货策略
 */
@Slf4j
@Component
@Order(1)
public class TextDeliveryStrategy implements DeliveryContentStrategy {

    @Autowired
    private FixedDeliveryTemplateService templateService;

    @Override
    public boolean supports(int deliveryMode) {
        return deliveryMode == 1;
    }

    @Override
    public String resolve(DeliveryContext context) {
        XianyuFixedDeliveryTemplate template = templateService.findOwnedTemplate(
                context.getAccountId(), context.getDeliveryConfig().getFixedTemplateId());
        if (template == null) {
            log.warn("【账号{}】固定内容发货模式下未选择有效模板: xyGoodsId={}",
                    context.getAccountId(), context.getXyGoodsId());
            return null;
        }
        context.setFixedTemplate(template);
        log.info("【账号{}】固定内容发货模式", context.getAccountId());
        return template.getDeliveryContent();
    }
}
