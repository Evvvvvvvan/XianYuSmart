package com.xianyusmart.service.delivery;

import com.xianyusmart.entity.XianyuKamiItem;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.KamiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 卡密发货策略
 *
 * <p>从卡密仓库获取可用卡密，返回本单全部卡密原始内容。</p>
 *
 * <h3>流程：</h3>
 * <ol>
 *   <li>遍历绑定的卡密配置ID列表（逗号分隔）</li>
 *   <li>按订单购买数量一次性预占完整卡密</li>
 *   <li>发送结果确认后统一提交或释放预占</li>
 *   <li>按购买数量组合为一份发货内容</li>
 * </ol>
 */
@Slf4j
@Component
@Order(2)
public class KamiDeliveryStrategy implements DeliveryContentStrategy {

    @Autowired
    private KamiConfigService kamiConfigService;

    @Override
    public boolean supports(int deliveryMode) {
        return deliveryMode == 2;
    }

    @Override
    public String resolve(DeliveryContext context) {
        String content = acquireKamiContent(
                context.getDeliveryConfig().getKamiConfigIds(),
                context.getOrderId(),
                context.getAccountId(),
                context.getXyGoodsId(),
                context.getSId(),
                context.getBuyerUserName(),
                context.getQuantity() != null ? context.getQuantity() : 1
        );
        if (content == null) {
            log.warn("【账号{}】卡密发货模式下无可用卡密: xyGoodsId={}, kamiConfigIds={}",
                    context.getAccountId(), context.getXyGoodsId(), context.getDeliveryConfig().getKamiConfigIds());
            return null;
        }
        log.info("【账号{}】卡密发货模式: content长度={}", context.getAccountId(), content.length());
        return content;
    }

    private String acquireKamiContent(String kamiConfigIds, String orderId,
                                       Long accountId, String xyGoodsId, String sId,
                                       String buyerUserName, int quantity) {
        if (kamiConfigIds == null || kamiConfigIds.trim().isEmpty()) {
            log.warn("【账号{}】卡密发货未绑定卡密配置: xyGoodsId={}", accountId, xyGoodsId);
            return null;
        }

        String[] configIdArr = kamiConfigIds.split(",");
        for (String configIdStr : configIdArr) {
            try {
                Long configId = Long.parseLong(configIdStr.trim());
                return kamiConfigService.reserveKami(configId, orderId, quantity).stream()
                        .map(XianyuKamiItem::getKamiContent)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse(null);
            } catch (NumberFormatException e) {
                log.warn("【账号{}】卡密配置ID格式错误: {}", accountId, configIdStr);
            } catch (BusinessException e) {
                log.warn("【账号{}】卡密配置无法满足订单: configId={}, orderId={}, reason={}",
                        accountId, configIdStr, orderId, e.getMessage());
                if (e.getCode() != 404 && !"卡密库存不足".equals(e.getMessage())) {
                    throw e;
                }
            }
        }
        return null;
    }

}
