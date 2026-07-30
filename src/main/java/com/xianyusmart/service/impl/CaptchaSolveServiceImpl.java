package com.xianyusmart.service.impl;

import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.CaptchaSolveService;
import com.xianyusmart.service.WebSocketService;
import com.xianyusmart.service.WebSocketTokenService;
import com.xianyusmart.service.captcha.CaptchaBrowserRunner;
import com.xianyusmart.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 滑块验证任务服务实现
 */
@Slf4j
@Service
public class CaptchaSolveServiceImpl implements CaptchaSolveService {

    private static final int MAX_BROWSER_TASKS = 2;
    private static final long TASK_RETENTION_MS = TimeUnit.MINUTES.toMillis(10);

    private final CaptchaBrowserRunner captchaBrowserRunner;
    private final WebSocketTokenService tokenService;
    private final AccountService accountService;
    private final WebSocketService webSocketService;
    private final Executor taskExecutor;
    private final Map<Long, TaskView> tasks = new ConcurrentHashMap<>();
    private final Semaphore browserPermits = new Semaphore(MAX_BROWSER_TASKS);

    public CaptchaSolveServiceImpl(CaptchaBrowserRunner captchaBrowserRunner,
                                   WebSocketTokenService tokenService,
                                   AccountService accountService,
                                   WebSocketService webSocketService,
                                   @Qualifier("taskExecutor") Executor taskExecutor) {
        this.captchaBrowserRunner = captchaBrowserRunner;
        this.tokenService = tokenService;
        this.accountService = accountService;
        this.webSocketService = webSocketService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public TaskView start(Long accountId, Mode mode) {
        if (accountId == null || mode == null) {
            throw new IllegalArgumentException("账号和验证方式不能为空");
        }
        cleanupFinishedTasks();

        TaskView pending;
        String captchaUrl;
        synchronized (tasks) {
            TaskView existing = tasks.get(accountId);
            if (isActive(existing)) {
                return existing;
            }

            captchaUrl = tokenService.getPendingCaptchaUrl(accountId);
            if (captchaUrl == null || captchaUrl.isBlank()) {
                throw new IllegalStateException("未找到有效的滑块验证任务，请重新启动连接");
            }
            if (!browserPermits.tryAcquire()) {
                throw new IllegalStateException("浏览器验证任务已满，请稍后重试");
            }

            long startedAt = System.currentTimeMillis();
            pending = new TaskView(accountId, mode, Status.PENDING, "验证任务已创建", startedAt, null);
            tasks.put(accountId, pending);
        }

        try {
            TaskView task = pending;
            String url = captchaUrl;
            taskExecutor.execute(() -> runTask(task, url));
        } catch (RuntimeException e) {
            browserPermits.release();
            finish(pending, Status.FAILED, "验证任务启动失败");
            throw e;
        }
        return tasks.get(accountId);
    }

    @Override
    public TaskView getStatus(Long accountId) {
        if (accountId == null) {
            return null;
        }
        cleanupFinishedTasks();
        return tasks.get(accountId);
    }

    private void runTask(TaskView task, String captchaUrl) {
        TaskView running = new TaskView(task.xianyuAccountId(), task.mode(), Status.RUNNING,
                "正在执行滑块验证", task.startedAt(), null);
        tasks.put(task.xianyuAccountId(), running);

        try {
            String currentCookie = accountService.getCookieByAccountId(task.xianyuAccountId());
            if (currentCookie == null || currentCookie.isBlank()) {
                finish(running, Status.FAILED, "账号Cookie不存在");
                return;
            }

            CaptchaBrowserRunner.RunResult result = captchaBrowserRunner.run(
                    task.xianyuAccountId(), task.mode(), captchaUrl, currentCookie);
            if (result == null || result.outcome() == null) {
                finish(running, Status.FAILED, "浏览器验证未返回结果");
                return;
            }
            if (result.outcome() != CaptchaBrowserRunner.Outcome.SOLVED) {
                finish(running, mapStatus(result.outcome()), safeMessage(result));
                return;
            }

            String refreshedCookie = result.cookieText();
            String unb = refreshedCookie == null
                    ? null
                    : XianyuSignUtils.parseCookies(refreshedCookie).get("unb");
            if (unb == null || unb.isBlank()) {
                finish(running, Status.FAILED, "验证完成但未获取到有效Cookie");
                return;
            }

            boolean updated = accountService.updateAccountCookie(
                    task.xianyuAccountId(), unb, refreshedCookie);
            boolean connected = updated
                    && webSocketService.restartAfterCredentialUpdate(task.xianyuAccountId());
            if (connected) {
                finish(running, Status.SUCCEEDED, "验证完成，Cookie已更新并重新连接");
            } else {
                finish(running, Status.FAILED, "验证完成，但凭证更新或重新连接失败");
            }
        } catch (Exception e) {
            log.error("【账号{}】滑块验证任务异常: {}", task.xianyuAccountId(),
                    e.getClass().getSimpleName());
            finish(running, Status.FAILED, "滑块验证执行异常");
        } finally {
            browserPermits.release();
        }
    }

    private Status mapStatus(CaptchaBrowserRunner.Outcome outcome) {
        return switch (outcome) {
            case TIMEOUT -> Status.TIMEOUT;
            case UNSUPPORTED -> Status.UNSUPPORTED;
            case FAILED -> Status.FAILED;
            case SOLVED -> Status.SUCCEEDED;
        };
    }

    private String safeMessage(CaptchaBrowserRunner.RunResult result) {
        if (result.message() == null || result.message().isBlank()) {
            return "滑块验证未完成";
        }
        return result.message();
    }

    private void finish(TaskView task, Status status, String message) {
        tasks.put(task.xianyuAccountId(), new TaskView(
                task.xianyuAccountId(),
                task.mode(),
                status,
                message,
                task.startedAt(),
                System.currentTimeMillis()));
    }

    private boolean isActive(TaskView task) {
        return task != null
                && (task.status() == Status.PENDING || task.status() == Status.RUNNING);
    }

    private void cleanupFinishedTasks() {
        long expiredBefore = System.currentTimeMillis() - TASK_RETENTION_MS;
        tasks.entrySet().removeIf(entry -> {
            TaskView task = entry.getValue();
            return !isActive(task)
                    && task.finishedAt() != null
                    && task.finishedAt() < expiredBefore;
        });
    }
}
