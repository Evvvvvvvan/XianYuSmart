package com.xianyusmart.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商家运营规则和任务调度器
 */
@Slf4j
@Component
public class MerchantOperationsScheduler {

    private final MerchantOperationsService operationsService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MerchantOperationsScheduler(MerchantOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @Scheduled(fixedDelayString = "${app.operations.dispatch-delay-ms:60000}", initialDelayString = "${app.automation.initial-delay-ms:60000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            operationsService.scheduleDueRules();
            operationsService.processDueTasks();
        } catch (Exception e) {
            log.error("商家运营任务调度异常", e);
        } finally {
            running.set(false);
        }
    }
}
