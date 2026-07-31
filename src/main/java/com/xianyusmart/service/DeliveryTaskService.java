package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryChannel;

import java.time.LocalDateTime;
import java.util.List;

public interface DeliveryTaskService {

    XianyuGoodsOrder discover(XianyuGoodsOrder order, DeliveryChannel channel);

    List<XianyuGoodsOrder> claimDueTasks(String workerId, int limit);

    void complete(Long taskId);

    void retryOrFail(Long taskId, String errorMessage);

    void deferForRisk(Long taskId, LocalDateTime retryAt, String errorMessage);

    void markReviewRequired(Long taskId, String errorMessage);

    void requeue(Long taskId);

    boolean requeueFailed(Long taskId, Long accountId);

    boolean markDelivered(Long taskId, Long accountId);
}
