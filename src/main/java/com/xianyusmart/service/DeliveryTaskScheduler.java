package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.enums.KamiStatus;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class DeliveryTaskScheduler {

    private final DeliveryTaskService deliveryTaskService;
    private final AutoDeliveryService autoDeliveryService;
    private final XianyuGoodsOrderMapper orderMapper;
    private final XianyuKamiItemMapper kamiItemMapper;
    private final XianyuAccountMapper accountMapper;
    private final OrderService orderService;
    private final PendingOrderPollService pendingOrderPollService;
    private final WebSocketService webSocketService;
    private final Executor taskExecutor;
    private final String workerId = buildWorkerId();

    @Value("${app.delivery.claim-batch-size:20}")
    private int claimBatchSize;

    public DeliveryTaskScheduler(DeliveryTaskService deliveryTaskService,
                                 AutoDeliveryService autoDeliveryService,
                                 XianyuGoodsOrderMapper orderMapper,
                                 XianyuKamiItemMapper kamiItemMapper,
                                 XianyuAccountMapper accountMapper,
                                 OrderService orderService,
                                 PendingOrderPollService pendingOrderPollService,
                                 WebSocketService webSocketService,
                                 @Qualifier("taskExecutor") Executor taskExecutor) {
        this.deliveryTaskService = deliveryTaskService;
        this.autoDeliveryService = autoDeliveryService;
        this.orderMapper = orderMapper;
        this.kamiItemMapper = kamiItemMapper;
        this.accountMapper = accountMapper;
        this.orderService = orderService;
        this.pendingOrderPollService = pendingOrderPollService;
        this.webSocketService = webSocketService;
        this.taskExecutor = taskExecutor;
    }

    @Scheduled(fixedDelayString = "${app.delivery.dispatch-delay-ms:1000}", initialDelay = 5000)
    public void dispatchDueTasks() {
        deliveryTaskService.claimDueTasks(workerId, claimBatchSize)
                .forEach(task -> taskExecutor.execute(() -> executeTask(task)));
    }

    @Scheduled(fixedDelay = 25000, initialDelay = 60000)
    public void discoverOrdersFromApi() {
        List<XianyuAccount> accounts = accountMapper.selectList(null);
        if (accounts == null) {
            return;
        }
        for (XianyuAccount account : accounts) {
            if (webSocketService.isConnected(account.getId())) {
                continue;
            }
            try {
                TenantContext.set(account.getTenantId());
                List<Map<String, Object>> pendingOrders = orderService.queryPendingOrders(account.getId());
                if (pendingOrders != null && !pendingOrders.isEmpty()) {
                    pendingOrderPollService.syncOrdersToDb(account.getId(), pendingOrders);
                }
            } catch (Exception e) {
                log.warn("【账号{}】待发货订单发现失败: {}", account.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void executeTask(XianyuGoodsOrder task) {
        try {
            TenantContext.set(task.getTenantId());
            String sId = task.getSid();
            if (sId == null || sId.isBlank()) {
                String receiverId = task.getBuyerUserId() != null ? task.getBuyerUserId() : task.getOrderId();
                sId = receiverId + "@goofish";
            }
            autoDeliveryService.executeDelivery(
                    task.getId(), task.getXianyuAccountId(), task.getXyGoodsId(), sId,
                    task.getOrderId(), task.getBuyerUserName(), false);

            XianyuGoodsOrder result = orderMapper.selectById(task.getId());
            if (result != null && Integer.valueOf(1).equals(result.getState())) {
                deliveryTaskService.complete(task.getId());
            } else if (result != null && DeliveryStatus.REVIEW_REQUIRED.name().equals(result.getDeliveryStatus())) {
                log.warn("订单发货结果待人工核对: taskId={}, orderId={}", task.getId(), task.getOrderId());
            } else if (kamiItemMapper.countByOrderAndStatus(task.getOrderId(), KamiStatus.REVIEW_REQUIRED.getCode()) > 0) {
                deliveryTaskService.markReviewRequired(task.getId(), result != null ? result.getFailReason() : null);
            } else {
                deliveryTaskService.retryOrFail(task.getId(), result != null ? result.getFailReason() : null);
            }
        } catch (Exception e) {
            log.error("订单任务执行异常: taskId={}, orderId={}", task.getId(), task.getOrderId(), e);
            deliveryTaskService.retryOrFail(task.getId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private String buildWorkerId() {
        String host = Optional.ofNullable(System.getenv("HOSTNAME"))
                .orElse(Optional.ofNullable(System.getenv("COMPUTERNAME")).orElse("local"));
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
