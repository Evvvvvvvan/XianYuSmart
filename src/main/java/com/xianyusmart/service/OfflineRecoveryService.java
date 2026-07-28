package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 连接恢复后的离线业务补偿
 */
@Slf4j
@Service
public class OfflineRecoveryService {

    private final XianyuAccountMapper accountMapper;
    private final OrderService orderService;
    private final PendingOrderPollService pendingOrderPollService;

    public OfflineRecoveryService(XianyuAccountMapper accountMapper,
                                  OrderService orderService,
                                  PendingOrderPollService pendingOrderPollService) {
        this.accountMapper = accountMapper;
        this.orderService = orderService;
        this.pendingOrderPollService = pendingOrderPollService;
    }

    @Async("taskExecutor")
    public void recover(Long accountId) {
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return;
        }
        try {
            TenantContext.set(account.getTenantId());
            // WebSocket 重连后立即拉取待发货订单，补偿断线期间未收到的下单事件。
            List<Map<String, Object>> orders = orderService.queryPendingOrders(accountId);
            pendingOrderPollService.syncOrdersToDb(accountId, orders);
            log.info("【账号{}】离线订单补偿完成: count={}", accountId, orders.size());
        } catch (Exception e) {
            log.warn("【账号{}】离线订单补偿失败: {}", accountId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
