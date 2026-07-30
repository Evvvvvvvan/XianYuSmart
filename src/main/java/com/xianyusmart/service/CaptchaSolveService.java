package com.xianyusmart.service;

/**
 * 滑块验证任务服务
 */
public interface CaptchaSolveService {

    enum Mode {
        AUTO,
        MANUAL_BROWSER
    }

    enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMEOUT,
        UNSUPPORTED,
        CANCELLED
    }

    record TaskView(Long xianyuAccountId, Mode mode, Status status,
                    String message, String phase, int attempt, int maxAttempts,
                    long startedAt, long updatedAt, long deadlineAt, Long finishedAt) {
    }

    TaskView start(Long accountId, Mode mode);

    TaskView getStatus(Long accountId);

    TaskView cancel(Long accountId);
}
