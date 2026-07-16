package com.xianyusmart.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商品运营自动化调度器
 */
@Slf4j
@Component
public class GoodsAutomationScheduler {

    private final GoodsAutomationService goodsAutomationService;
    private final AtomicBoolean rating = new AtomicBoolean(false);
    private final AtomicBoolean polishing = new AtomicBoolean(false);

    public GoodsAutomationScheduler(GoodsAutomationService goodsAutomationService) {
        this.goodsAutomationService = goodsAutomationService;
    }

    @Scheduled(fixedDelayString = "${app.automation.rate-delay-ms:600000}", initialDelayString = "${app.automation.initial-delay-ms:60000}")
    public void rateEnabledGoods() {
        if (!rating.compareAndSet(false, true)) {
            return;
        }
        try {
            goodsAutomationService.runAutoRate();
        } catch (Exception e) {
            log.error("自动评价任务执行异常", e);
        } finally {
            rating.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${app.automation.polish-delay-ms:3600000}", initialDelayString = "${app.automation.initial-delay-ms:60000}")
    public void polishEnabledGoods() {
        if (!polishing.compareAndSet(false, true)) {
            return;
        }
        try {
            goodsAutomationService.runAutoPolish();
        } catch (Exception e) {
            log.error("自动擦亮任务执行异常", e);
        } finally {
            polishing.set(false);
        }
    }
}
