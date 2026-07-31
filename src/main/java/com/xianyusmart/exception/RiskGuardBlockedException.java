package com.xianyusmart.exception;

import com.xianyusmart.service.RiskControlService;

/**
 * 平台写操作需要等待恢复
 */
public class RiskGuardBlockedException extends RuntimeException {

    private final long retryAt;

    public RiskGuardBlockedException(RiskControlService.GuardDecision decision) {
        super(decision.state() == RiskControlService.GuardState.CIRCUIT_OPEN
                ? "账号风控冷却中，剩余" + decision.remainingSeconds() + "秒"
                : "写操作过于频繁，请" + decision.remainingSeconds() + "秒后重试");
        this.retryAt = decision.retryAt();
    }

    public long getRetryAt() {
        return retryAt;
    }
}
