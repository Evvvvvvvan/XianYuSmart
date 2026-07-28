package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 连接恢复后的离线业务补偿
 */
@Slf4j
@Service
public class OfflineRecoveryService {

    private final XianyuAccountMapper accountMapper;
    private final ObjectProvider<PendingOrderPollService> pendingOrderPollServiceProvider;

    public OfflineRecoveryService(XianyuAccountMapper accountMapper,
                                  ObjectProvider<PendingOrderPollService> pendingOrderPollServiceProvider) {
        this.accountMapper = accountMapper;
        this.pendingOrderPollServiceProvider = pendingOrderPollServiceProvider;
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
            int queuedCount = pendingOrderPollServiceProvider.getObject().deliverPendingOrders(accountId);
            log.info("【账号{}】离线订单补偿完成: queued={}", accountId, queuedCount);
        } catch (Exception e) {
            log.warn("【账号{}】离线订单补偿失败: {}", accountId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
