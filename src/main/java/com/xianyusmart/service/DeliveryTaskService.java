package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.enums.DeliveryChannel;

import java.util.List;

public interface DeliveryTaskService {

    XianyuGoodsOrder discover(XianyuGoodsOrder order, DeliveryChannel channel);

    List<XianyuGoodsOrder> claimDueTasks(String workerId, int limit);

    void complete(Long taskId);

    void retryOrFail(Long taskId, String errorMessage);

    void markReviewRequired(Long taskId, String errorMessage);

    void requeue(Long taskId);

    boolean requeueFailed(Long taskId, Long accountId);
}
