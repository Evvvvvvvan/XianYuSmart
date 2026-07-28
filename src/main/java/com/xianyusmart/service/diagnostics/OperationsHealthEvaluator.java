package com.xianyusmart.service.diagnostics;

/**
 * 运营健康状态汇总规则
 */
public final class OperationsHealthEvaluator {

    private OperationsHealthEvaluator() {
    }

    public static String overallStatus(long criticalCount, long warningCount) {
        if (criticalCount > 0) {
            return "CRITICAL";
        }
        return warningCount > 0 ? "WARNING" : "HEALTHY";
    }
}
