package com.xianyusmart.service.impl;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryChannel;
import com.xianyusmart.enums.DeliveryStatus;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.DeliveryTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class DeliveryTaskServiceImpl implements DeliveryTaskService {

    private final XianyuGoodsOrderMapper orderMapper;

    @Value("${app.delivery.lease-seconds:120}")
    private int leaseSeconds;

    @Value("${app.delivery.max-attempts:3}")
    private int maxAttempts;

    public DeliveryTaskServiceImpl(XianyuGoodsOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public XianyuGoodsOrder discover(XianyuGoodsOrder order, DeliveryChannel channel) {
        int quantity = order.getBuyNum() != null && order.getBuyNum() > 0 ? order.getBuyNum() : 1;
        order.setDeliveryStatus(DeliveryStatus.PENDING.name());
        order.setExpectedQuantity(quantity);
        order.setDeliveryChannel(channel.name());
        orderMapper.insert(order);

        XianyuGoodsOrder persisted = order.getId() != null ? orderMapper.selectById(order.getId()) : null;
        if (persisted == null && order.getOrderId() != null) {
            persisted = orderMapper.selectByAccountIdAndOrderId(order.getXianyuAccountId(), order.getOrderId());
        }
        if (persisted == null && order.getPnmId() != null) {
            persisted = orderMapper.selectByPnmId(order.getXianyuAccountId(), order.getPnmId());
        }
        if (persisted == null) {
            throw new IllegalStateException("订单任务持久化失败");
        }
        return persisted;
    }

    @Override
    @Transactional
    public List<XianyuGoodsOrder> claimDueTasks(String workerId, int limit) {
        int batchSize = Math.max(1, Math.min(limit, 100));
        List<XianyuGoodsOrder> tasks = orderMapper.lockDueTasks(batchSize);
        if (tasks.isEmpty()) {
            return tasks;
        }
        List<Long> taskIds = tasks.stream().map(XianyuGoodsOrder::getId).toList();
        if (orderMapper.claimTasks(taskIds, workerId, leaseSeconds) != taskIds.size()) {
            throw new IllegalStateException("订单任务租约领取冲突");
        }
        tasks.forEach(task -> {
            task.setDeliveryStatus(DeliveryStatus.PROCESSING.name());
            task.setAttemptCount((task.getAttemptCount() != null ? task.getAttemptCount() : 0) + 1);
        });
        return tasks;
    }

    @Override
    public void complete(Long taskId) {
        orderMapper.completeTask(taskId);
    }

    @Override
    public void retryOrFail(Long taskId, String errorMessage) {
        XianyuGoodsOrder task = orderMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int attempts = task.getAttemptCount() != null ? task.getAttemptCount() : 0;
        boolean exhausted = attempts >= maxAttempts;
        String status = exhausted ? DeliveryStatus.FAILED.name() : DeliveryStatus.RETRY_WAIT.name();
        LocalDateTime nextRetryTime = exhausted ? null : LocalDateTime.now().plusSeconds(Math.min(300L, 5L << attempts));
        String safeMessage = errorMessage == null ? "自动发货失败" : errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        orderMapper.retryOrFailTask(taskId, status, nextRetryTime, safeMessage);
    }

    @Override
    public void markReviewRequired(Long taskId, String errorMessage) {
        String safeMessage = errorMessage == null ? "发送结果不确定，请人工核对" :
                errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        orderMapper.markTaskReviewRequired(taskId, safeMessage);
    }

    @Override
    public void requeue(Long taskId) {
        orderMapper.requeueTask(taskId);
    }

    @Override
    public boolean requeueFailed(Long taskId, Long accountId) {
        // 账号和失败状态同时进入更新条件，保证并发重试只成功一次。
        return orderMapper.requeueFailedTask(taskId, accountId) == 1;
    }
}
