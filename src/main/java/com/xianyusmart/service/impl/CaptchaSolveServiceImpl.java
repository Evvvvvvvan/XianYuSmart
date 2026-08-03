package com.xianyusmart.service.impl;

import com.xianyusmart.exception.CaptchaRequiredException;
import com.xianyusmart.exception.CookieExpiredException;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 滑块验证任务服务实现
 */
@Slf4j
@Service
public class CaptchaSolveServiceImpl implements CaptchaSolveService {

    private static final int MAX_BROWSER_TASKS = 2;
    private static final int MAX_AUTO_ATTEMPTS = 5;
    private static final long TASK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long TASK_RETENTION_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long HEARTBEAT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long BROWSER_WAIT_NOTICE_MS = TimeUnit.SECONDS.toMillis(5);

    private final CaptchaBrowserRunner captchaBrowserRunner;
    private final WebSocketTokenService tokenService;
    private final AccountService accountService;
    private final WebSocketService webSocketService;
    private final Executor taskExecutor;
    private final ScheduledExecutorService taskScheduler;
    private final Map<Long, TaskView> tasks = new ConcurrentHashMap<>();
    private final Map<Long, TaskControl> taskControls = new ConcurrentHashMap<>();
    private final Semaphore browserPermits = new Semaphore(MAX_BROWSER_TASKS);

    public CaptchaSolveServiceImpl(CaptchaBrowserRunner captchaBrowserRunner,
                                   WebSocketTokenService tokenService,
                                   AccountService accountService,
                                   WebSocketService webSocketService,
                                   @Qualifier("taskExecutor") Executor taskExecutor,
                                   @Qualifier("captchaTaskScheduler") ScheduledExecutorService taskScheduler) {
        this.captchaBrowserRunner = captchaBrowserRunner;
        this.tokenService = tokenService;
        this.accountService = accountService;
        this.webSocketService = webSocketService;
        this.taskExecutor = taskExecutor;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public TaskView start(Long accountId, Mode mode) {
        if (accountId == null || mode == null) {
            throw new IllegalArgumentException("账号和验证方式不能为空");
        }
        cleanupFinishedTasks();

        synchronized (tasks) {
            TaskView existing = tasks.get(accountId);
            if (isActive(existing)) {
                if (existing.mode() != mode) {
                    throw new IllegalStateException("当前已有其他方式的验证任务，请先取消当前任务");
                }
                return existing;
            }
            if (taskControls.containsKey(accountId)) {
                throw new IllegalStateException("上一个验证任务正在退出，请稍后重试");
            }

            String captchaUrl = tokenService.getPendingCaptchaUrl(accountId);
            if (captchaUrl == null || captchaUrl.isBlank()) {
                throw new IllegalStateException("未找到有效的滑块验证任务，请重新启动连接");
            }
            if (!browserPermits.tryAcquire()) {
                throw new IllegalStateException("浏览器验证任务已满，请稍后重试");
            }

            long startedAt = System.currentTimeMillis();
            TaskView pending = new TaskView(accountId, mode, Status.PENDING, "验证任务已创建",
                    "QUEUED", 0, MAX_AUTO_ATTEMPTS, startedAt, startedAt,
                    startedAt + TASK_TIMEOUT_MS, null);
            TaskControl control = new TaskControl(pending);
            control.future = new FutureTask<>(() -> {
                runTask(pending, captchaUrl, control);
                return null;
            });
            try {
                control.timeoutFuture = taskScheduler.schedule(
                        () -> timeoutTask(control), TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                control.heartbeatFuture = taskScheduler.scheduleAtFixedRate(
                        () -> heartbeat(control), HEARTBEAT_INTERVAL_MS,
                        HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
                // 执行句柄和定时任务完整后再发布，确保立即取消也能终止任务。
                taskControls.put(accountId, control);
                tasks.put(accountId, pending);
                taskExecutor.execute(control.future);
            } catch (RuntimeException e) {
                complete(control, Status.FAILED, "验证任务启动失败");
                control.future.cancel(false);
                cleanupControl(control);
                throw e;
            }
            return tasks.get(accountId);
        }
    }

    @Override
    public TaskView getStatus(Long accountId) {
        if (accountId == null) {
            return null;
        }
        cleanupFinishedTasks();
        return tasks.get(accountId);
    }

    @Override
    public TaskView cancel(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("账号不能为空");
        }
        TaskView current = tasks.get(accountId);
        if (!isActive(current)) {
            return current;
        }
        TaskControl control = taskControls.get(accountId);
        if (control == null) {
            return current;
        }

        synchronized (control.startLock) {
            boolean sideEffectLocked = control.sideEffectLock.tryLock();
            try {
                if (complete(control, Status.CANCELLED, "滑块验证已取消")) {
                    FutureTask<CaptchaBrowserRunner.RunResult> browserFuture = control.browserFuture;
                    if (browserFuture != null) {
                        browserFuture.cancel(true);
                    }
                    cancelBrowser(control.accountId);
                    FutureTask<Void> future = control.future;
                    if (future != null) {
                        future.cancel(true);
                    }
                }
            } finally {
                if (sideEffectLocked) {
                    control.sideEffectLock.unlock();
                }
            }
        }
        if (!control.started.get()) {
            cleanupControl(control);
        }
        return tasks.get(accountId);
    }

    @Override
    public ManualFrame getManualFrame(Long accountId) {
        requireManualTask(accountId);
        ManualFrame frame = captchaBrowserRunner.getManualFrame(accountId);
        if (frame == null) {
            throw new IllegalStateException("人工浏览器画面正在生成");
        }
        return frame;
    }

    @Override
    public TaskView submitManualDrag(Long accountId, ManualDrag drag) {
        requireManualTask(accountId);
        captchaBrowserRunner.submitManualDrag(accountId, drag);
        return tasks.get(accountId);
    }

    private TaskView requireManualTask(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("账号不能为空");
        }
        TaskView task = tasks.get(accountId);
        if (!isActive(task) || task.mode() != Mode.MANUAL_BROWSER) {
            throw new IllegalStateException("当前没有运行中的人工滑块任务");
        }
        return task;
    }

    private void runTask(TaskView task, String captchaUrl, TaskControl control) {
        try {
            String activeCaptchaUrl = captchaUrl;
            control.started.set(true);
            if (!isCurrentActive(tasks.get(control.accountId), control)) {
                return;
            }
            updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
                    "STARTING_BROWSER", "正在启动浏览器", 0, MAX_AUTO_ATTEMPTS));

            if (task.mode() == Mode.AUTO) {
                updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
                        "PAUSING_RECONNECT", "正在暂停后台重连，避免占用浏览器资源",
                        0, MAX_AUTO_ATTEMPTS));
                // 自动验证期间暂停同账号后台重连，避免两个浏览器任务争抢内存和页面响应。
                webSocketService.stopWebSocket(task.xianyuAccountId());
                updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
                        "REFRESHING_CHALLENGE", "正在刷新验证会话，避免使用过期滑块",
                        0, MAX_AUTO_ATTEMPTS));
                // 自动模式先刷新平台验证会话，已恢复时无需继续打开旧滑块页面。
                tokenService.clearCaptchaWait(task.xianyuAccountId());
                try {
                    String refreshedToken = tokenService.refreshToken(task.xianyuAccountId());
                    if (refreshedToken != null && !refreshedToken.isBlank()) {
                        updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
                                "RECONNECTING", "凭证已恢复，正在重新连接",
                                0, MAX_AUTO_ATTEMPTS));
                        boolean connected = webSocketService.restartAfterCredentialUpdate(
                                task.xianyuAccountId());
                        complete(control,
                                connected ? Status.SUCCEEDED : Status.FAILED,
                                connected
                                        ? "凭证已自动恢复并重新连接"
                                        : "凭证已恢复，但重新连接失败");
                        return;
                    }
                } catch (CaptchaRequiredException e) {
                    if (e.getCaptchaUrl() != null && !e.getCaptchaUrl().isBlank()) {
                        activeCaptchaUrl = e.getCaptchaUrl();
                    }
                } catch (CookieExpiredException e) {
                    complete(control, Status.FAILED,
                            "Cookie Session已过期，请重新扫码登录后再连接");
                    return;
                }
            }

            String currentCookie = accountService.getCookieByAccountId(task.xianyuAccountId());
            if (currentCookie == null || currentCookie.isBlank()) {
                complete(control, Status.FAILED, "账号Cookie不存在");
                return;
            }

            String browserCaptchaUrl = activeCaptchaUrl;
            FutureTask<CaptchaBrowserRunner.RunResult> browserFuture = new FutureTask<>(
                    () -> captchaBrowserRunner.run(
                            task.xianyuAccountId(), task.mode(), browserCaptchaUrl, currentCookie,
                            progress -> updateProgress(control, progress)));
            synchronized (control.startLock) {
                if (!isCurrentActive(tasks.get(control.accountId), control)
                        || Thread.currentThread().isInterrupted()) {
                    return;
                }
                // 启动检查与取消互斥，取消先完成时禁止创建浏览器。
                control.browserFuture = browserFuture;
            }
            browserFuture.run();
            CaptchaBrowserRunner.RunResult result = browserFuture.get();
            if (result == null || result.outcome() == null) {
                complete(control, Status.FAILED, "浏览器验证未返回结果");
                return;
            }
            if (result.outcome() != CaptchaBrowserRunner.Outcome.SOLVED) {
                complete(control, mapStatus(result.outcome()), safeMessage(result));
                return;
            }
            if (!isCurrentActive(tasks.get(control.accountId), control)) {
                return;
            }

            updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
                    "UPDATING_COOKIE", "正在更新Cookie并恢复连接",
                    control.attempt, control.maxAttempts));
            String refreshedCookie = result.cookieText();
            String unb = XianyuSignUtils.extractUserId(refreshedCookie);
            if (unb == null || unb.isBlank()) {
                complete(control, Status.FAILED, "验证完成但未获取到有效Cookie");
                return;
            }
            String normalizedCookie = XianyuSignUtils.normalizeCookieUserId(
                    refreshedCookie, unb);

            control.sideEffectLock.lock();
            try {
                if (!isCurrentActive(tasks.get(control.accountId), control)) {
                    return;
                }
                boolean updated = accountService.updateAccountCookie(
                        task.xianyuAccountId(), unb, normalizedCookie);
                if (!updated) {
                    complete(control, Status.FAILED, "验证完成，但凭证更新或重新连接失败");
                    return;
                }
            } finally {
                control.sideEffectLock.unlock();
            }
            updateProgress(control, new CaptchaBrowserRunner.ProgressUpdate(
                    "VALIDATING_CREDENTIAL", "正在确认平台已放行新凭证",
                    control.attempt, control.maxAttempts));
            control.sideEffectLock.lock();
            try {
                if (!isCurrentActive(tasks.get(control.accountId), control)) {
                    return;
                }
                boolean connected = webSocketService.restartAfterCredentialUpdate(task.xianyuAccountId());
                if (connected) {
                    complete(control, Status.SUCCEEDED, "验证完成，Cookie已更新并重新连接");
                } else {
                    complete(control, Status.FAILED,
                            credentialFailureMessage(pendingCaptchaUrl(task.xianyuAccountId())));
                }
            } finally {
                control.sideEffectLock.unlock();
            }
        } catch (Exception e) {
            if (isCurrentActive(tasks.get(control.accountId), control)) {
                log.error("【账号{}】滑块验证任务异常: {}", task.xianyuAccountId(),
                        e.getClass().getSimpleName());
                complete(control, Status.FAILED, "滑块验证执行异常");
            } else {
                log.debug("【账号{}】滑块验证任务已终止", task.xianyuAccountId());
            }
        } finally {
            cleanupControl(control);
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

    private String pendingCaptchaUrl(Long accountId) {
        try {
            return tokenService.getPendingCaptchaUrl(accountId);
        } catch (Exception e) {
            log.warn("【账号{}】读取平台验证状态失败: {}", accountId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    static String credentialFailureMessage(String pendingCaptchaUrl) {
        return pendingCaptchaUrl == null || pendingCaptchaUrl.isBlank()
                ? "凭证已更新，但WebSocket重新连接失败"
                : "滑块操作完成，但平台仍要求验证";
    }

    private void updateProgress(TaskControl control, CaptchaBrowserRunner.ProgressUpdate progress) {
        if (progress == null || taskControls.get(control.accountId) != control) {
            return;
        }
        long now = System.currentTimeMillis();
        control.lastProgressAt = now;
        control.phase = progress.phase();
        control.message = progress.message();
        control.attempt = progress.attempt();
        control.maxAttempts = progress.maxAttempts();
        tasks.compute(control.accountId, (accountId, current) -> {
            if (!isCurrentActive(current, control)) {
                return current;
            }
            return new TaskView(accountId, current.mode(), Status.RUNNING,
                    progress.message(), progress.phase(), progress.attempt(),
                    progress.maxAttempts(), current.startedAt(), now,
                    current.deadlineAt(), null);
        });
    }

    private void heartbeat(TaskControl control) {
        if (taskControls.get(control.accountId) != control) {
            return;
        }
        long now = System.currentTimeMillis();
        tasks.compute(control.accountId, (accountId, current) -> {
            if (!isCurrentActive(current, control)) {
                return current;
            }
            boolean waiting = now - control.lastProgressAt >= BROWSER_WAIT_NOTICE_MS;
            String phase = waiting ? "WAITING_BROWSER" : control.phase;
            String baseMessage = waiting
                    ? "浏览器响应等待中，上一步：" + control.message
                    : control.message;
            long elapsedSeconds = Math.max(0, (now - current.startedAt()) / 1000);
            long remainingSeconds = Math.max(0, (current.deadlineAt() - now) / 1000);
            String message = baseMessage + "（已用时" + elapsedSeconds
                    + "秒，剩余" + remainingSeconds + "秒）";
            return new TaskView(accountId, current.mode(), current.status(),
                    message, phase, control.attempt, control.maxAttempts,
                    current.startedAt(), now, current.deadlineAt(), null);
        });
    }

    private void timeoutTask(TaskControl control) {
        synchronized (control.startLock) {
            boolean sideEffectLocked = control.sideEffectLock.tryLock();
            try {
                if (!complete(control, Status.TIMEOUT, "滑块验证超时，浏览器任务已终止")) {
                    return;
                }
                FutureTask<CaptchaBrowserRunner.RunResult> browserFuture = control.browserFuture;
                if (browserFuture != null) {
                    browserFuture.cancel(true);
                }
                cancelBrowser(control.accountId);
                FutureTask<Void> future = control.future;
                if (future != null) {
                    future.cancel(true);
                }
            } finally {
                if (sideEffectLocked) {
                    control.sideEffectLock.unlock();
                }
            }
        }
        if (!control.started.get()) {
            cleanupControl(control);
        }
    }

    private void cancelBrowser(Long accountId) {
        try {
            captchaBrowserRunner.cancel(accountId);
        } catch (Exception e) {
            log.warn("【账号{}】终止滑块浏览器进程失败: {}", accountId,
                    e.getClass().getSimpleName());
        }
    }

    private boolean complete(TaskControl control, Status status, String message) {
        long now = System.currentTimeMillis();
        AtomicBoolean completed = new AtomicBoolean();
        String finalPhase = status == Status.FAILED
                || status == Status.TIMEOUT
                || status == Status.UNSUPPORTED
                ? control.phase
                : status.name();
        tasks.compute(control.accountId, (accountId, current) -> {
            if (!isCurrentActive(current, control)) {
                return current;
            }
            completed.set(true);
            return new TaskView(accountId, current.mode(), status, message,
                    finalPhase, control.attempt, control.maxAttempts,
                    current.startedAt(), now, current.deadlineAt(), now);
        });
        return completed.get();
    }

    private boolean isCurrentActive(TaskView task, TaskControl control) {
        return taskControls.get(control.accountId) == control
                && task != null
                && task.startedAt() == control.startedAt
                && isActive(task);
    }

    private void cleanupControl(TaskControl control) {
        taskControls.remove(control.accountId, control);
        ScheduledFuture<?> timeoutFuture = control.timeoutFuture;
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        ScheduledFuture<?> heartbeatFuture = control.heartbeatFuture;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (control.permitReleased.compareAndSet(false, true)) {
            browserPermits.release();
        }
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

    private static final class TaskControl {
        private final Long accountId;
        private final long startedAt;
        private final Object startLock = new Object();
        private final ReentrantLock sideEffectLock = new ReentrantLock();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean permitReleased = new AtomicBoolean();
        private volatile FutureTask<Void> future;
        private volatile FutureTask<CaptchaBrowserRunner.RunResult> browserFuture;
        private volatile ScheduledFuture<?> timeoutFuture;
        private volatile ScheduledFuture<?> heartbeatFuture;
        private volatile long lastProgressAt;
        private volatile String phase = "QUEUED";
        private volatile String message = "验证任务已创建";
        private volatile int attempt;
        private volatile int maxAttempts = MAX_AUTO_ATTEMPTS;

        private TaskControl(TaskView task) {
            this.accountId = task.xianyuAccountId();
            this.startedAt = task.startedAt();
            this.lastProgressAt = task.startedAt();
        }
    }
}
