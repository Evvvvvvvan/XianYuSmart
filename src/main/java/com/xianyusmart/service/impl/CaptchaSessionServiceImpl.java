package com.xianyusmart.service.impl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.constants.OperationConstants;
import com.xianyusmart.exception.CaptchaRequiredException;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.CaptchaSessionService;
import com.xianyusmart.service.CookieRefreshService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.WebSocketService;
import com.xianyusmart.service.WebSocketTokenService;
import com.xianyusmart.utils.XianyuSignUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务器滑块验证会话实现
 */
@Slf4j
@Service
public class CaptchaSessionServiceImpl implements CaptchaSessionService {

    private static final long SESSION_TTL_MS = Duration.ofMinutes(2).toMillis();
    private static final int MAX_DRAG_POINTS = 160;
    private static final String GOOFISH_COOKIE_DOMAIN = ".goofish.com";
    private static final String TAOBAO_COOKIE_DOMAIN = ".taobao.com";
    private static final List<String> TRUSTED_CAPTCHA_DOMAINS = List.of(
            "goofish.com", "taobao.com", "tmall.com", "alibaba.com");

    private final PlaywrightManager playwrightManager;
    private final AccountService accountService;
    private final CookieRefreshService cookieRefreshService;
    private final WebSocketService webSocketService;
    private final OperationLogService operationLogService;
    private final WebSocketTokenService tokenService;
    private final ExecutorService captchaExecutor;
    private final Map<String, CaptchaSession> sessions = new HashMap<>();
    private final Map<Long, String> accountSessions = new HashMap<>();

    public CaptchaSessionServiceImpl(
            PlaywrightManager playwrightManager,
            AccountService accountService,
            CookieRefreshService cookieRefreshService,
            WebSocketService webSocketService,
            OperationLogService operationLogService,
            WebSocketTokenService tokenService) {
        this.playwrightManager = playwrightManager;
        this.accountService = accountService;
        this.cookieRefreshService = cookieRefreshService;
        this.webSocketService = webSocketService;
        this.operationLogService = operationLogService;
        this.tokenService = tokenService;
        this.captchaExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "xys-captcha-browser");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CaptchaSessionResult startSession(Long accountId, String captchaUrl) {
        if (accountId == null) {
            throw new IllegalArgumentException("账号ID不能为空");
        }
        if (!isTrustedCaptchaUrl(captchaUrl)) {
            throw new IllegalArgumentException("未获取到可信的滑块验证链接，请重新启动连接");
        }
        return execute(() -> doStartSession(accountId, captchaUrl), 35);
    }

    @Override
    public CaptchaSessionResult replayDrag(Long accountId, String sessionId, List<DragPoint> points) {
        if (accountId == null || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("滑块验证会话无效");
        }
        if (points == null || points.size() < 2 || points.size() > MAX_DRAG_POINTS) {
            throw new IllegalArgumentException("滑块拖动轨迹无效");
        }
        return execute(() -> doReplayDrag(accountId, sessionId, points), 20);
    }

    @Override
    public void closeSession(Long accountId, String sessionId) {
        if (accountId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        execute(() -> {
            CaptchaSession session = sessions.get(sessionId);
            if (session != null && Objects.equals(session.accountId(), accountId)) {
                closeSessionInternal(sessionId);
            }
            return null;
        }, 5);
    }

    private CaptchaSessionResult doStartSession(Long accountId, String captchaUrl) {
        cleanupExpiredSessions();
        String existingSessionId = accountSessions.get(accountId);
        if (existingSessionId != null) {
            closeSessionInternal(existingSessionId);
        }

        String cookieText = accountService.getCookieByAccountId(accountId);
        Map<String, String> existingCookies = XianyuSignUtils.parseCookies(cookieText);
        if (existingCookies.isEmpty()) {
            throw new IllegalArgumentException("账号Cookie为空，请先扫码登录");
        }

        BrowserContext context = null;
        try {
            context = playwrightManager.createContext();
            context.addCookies(buildBrowserCookies(existingCookies));
            Page page = context.newPage();
            // 隐藏自动化标记，避免人工验证页面直接拒绝服务器浏览器
            page.addInitScript("Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");
            navigateCaptchaPage(page, captchaUrl);
            if (isExpiredCaptchaPage(page.content())) {
                // 验证链接有效期较短，仅在过期页出现时重新请求，避免增加风控请求频率。
                log.warn("【账号{}】滑块验证链接已过期，重新获取验证链接", accountId);
                String refreshedCaptchaUrl = refreshCaptchaUrl(accountId);
                if (refreshedCaptchaUrl == null) {
                    closeContext(context);
                    context = null;
                    boolean connected = webSocketService.restartAfterCredentialUpdate(accountId);
                    return new CaptchaSessionResult(
                            "",
                            null,
                            true,
                            connected,
                            connected ? "风控已解除，WebSocket已重连" : "风控已解除，WebSocket正在重连");
                }
                if (!isTrustedCaptchaUrl(refreshedCaptchaUrl)) {
                    throw new IllegalArgumentException("重新获取的滑块验证链接不可信");
                }
                navigateCaptchaPage(page, refreshedCaptchaUrl);
                if (isExpiredCaptchaPage(page.content())) {
                    throw new IllegalStateException("滑块验证链接已过期，请重新启动连接");
                }
            }
            if (!isTrustedCaptchaUrl(page.url())) {
                throw new IllegalArgumentException("滑块验证页面跳转到了非可信地址");
            }

            String sessionId = UUID.randomUUID().toString();
            CaptchaSession session = new CaptchaSession(
                    sessionId,
                    accountId,
                    context,
                    page,
                    existingCookies.get("x5sec"),
                    System.currentTimeMillis());
            sessions.put(sessionId, session);
            accountSessions.put(accountId, sessionId);

            log.info("【账号{}】服务器滑块验证会话已创建: sessionId={}", accountId, sessionId);
            return new CaptchaSessionResult(
                    sessionId,
                    captureScreenshot(page),
                    false,
                    false,
                    "请在验证画面中拖动滑块");
        } catch (Exception e) {
            if (context != null) {
                closeContext(context);
            }
            throw new IllegalStateException("加载滑块验证页面失败: " + e.getMessage(), e);
        }
    }

    private void navigateCaptchaPage(Page page, String captchaUrl) {
        page.navigate(captchaUrl, new Page.NavigateOptions()
                .setTimeout(20_000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForTimeout(800);
    }

    private static boolean isExpiredCaptchaPage(String pageContent) {
        return pageContent != null && (pageContent.contains("抱歉，页面访问出现了问题")
                || pageContent.contains("Sorry, there was a problem accessing the page"));
    }

    private String refreshCaptchaUrl(Long accountId) {
        tokenService.clearCaptchaWait(accountId);
        try {
            String accessToken = tokenService.refreshToken(accountId);
            if (accessToken != null && !accessToken.isBlank()) {
                return null;
            }
            throw new IllegalStateException("重新获取滑块验证链接失败");
        } catch (CaptchaRequiredException e) {
            return e.getCaptchaUrl();
        }
    }

    private CaptchaSessionResult doReplayDrag(Long accountId, String sessionId, List<DragPoint> points) {
        cleanupExpiredSessions();
        CaptchaSession session = sessions.get(sessionId);
        if (session == null || !Objects.equals(session.accountId(), accountId)) {
            throw new IllegalArgumentException("滑块验证会话已失效，请重新开始");
        }

        replayMouse(session.page(), points);
        session.page().waitForTimeout(1200);
        List<Cookie> browserCookies = session.context().cookies();
        String updatedX5sec = findUpdatedX5sec(browserCookies, session.initialX5sec());
        if (updatedX5sec == null) {
            sessions.put(sessionId, session.touch());
            return new CaptchaSessionResult(
                    sessionId,
                    captureScreenshot(session.page()),
                    false,
                    false,
                    "验证尚未通过，请按画面提示重试");
        }

        String currentCookie = accountService.getCookieByAccountId(accountId);
        String mergedCookie = mergeCookieText(currentCookie, browserCookies);
        mergedCookie = cookieRefreshService.clearDuplicateCookies(mergedCookie + "; x5sec=" + updatedX5sec);
        Map<String, String> mergedCookies = XianyuSignUtils.parseCookies(mergedCookie);
        String unb = mergedCookies.get("unb");
        if (unb == null || unb.isBlank() || !accountService.updateAccountCookie(accountId, unb, mergedCookie)) {
            closeSessionInternal(sessionId);
            throw new IllegalStateException("滑块验证已通过，但Cookie保存失败");
        }

        boolean connected = webSocketService.restartAfterCredentialUpdate(accountId);
        closeSessionInternal(sessionId);
        try {
            operationLogService.log(
                    accountId,
                    OperationConstants.Type.VERIFY,
                    OperationConstants.Module.COOKIE,
                    "服务器滑块验证完成并更新Cookie",
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, null, null);
        } catch (Exception e) {
            log.warn("【账号{}】记录滑块验证操作日志失败: {}", accountId, e.getMessage());
        }

        return new CaptchaSessionResult(
                sessionId,
                null,
                true,
                connected,
                connected ? "滑块验证成功，WebSocket已重连" : "滑块验证成功，WebSocket正在重连");
    }

    private void replayMouse(Page page, List<DragPoint> points) {
        DragPoint first = points.get(0);
        validatePoint(first);
        page.mouse().move(first.x(), first.y());
        page.mouse().down();
        try {
            for (int i = 1; i < points.size(); i++) {
                DragPoint point = points.get(i);
                validatePoint(point);
                page.mouse().move(point.x(), point.y());
                if (point.delayMs() > 0) {
                    Thread.sleep(Math.min(point.delayMs(), 40));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("滑块拖动已中断", e);
        } finally {
            page.mouse().up();
        }
    }

    private void validatePoint(DragPoint point) {
        if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                || point.x() < 0 || point.y() < 0 || point.x() > 4096 || point.y() > 4096) {
            throw new IllegalArgumentException("滑块拖动坐标无效");
        }
    }

    boolean isTrustedCaptchaUrl(String captchaUrl) {
        if (captchaUrl == null || captchaUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(captchaUrl);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase();
            return TRUSTED_CAPTCHA_DOMAINS.stream()
                    .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
        } catch (Exception e) {
            return false;
        }
    }

    String mergeCookieText(String currentCookie, List<Cookie> browserCookies) {
        Map<String, String> browserCookieMap = new LinkedHashMap<>();
        for (Cookie cookie : browserCookies) {
            if (cookie.name == null || cookie.name.isBlank() || cookie.value == null || cookie.value.isBlank()) {
                continue;
            }
            browserCookieMap.put(cookie.name, cookie.value);
        }
        String browserCookieText = XianyuSignUtils.formatCookies(browserCookieMap);
        if (browserCookieText.isBlank()) {
            return currentCookie;
        }
        return cookieRefreshService.clearDuplicateCookies(currentCookie + "; " + browserCookieText);
    }

    private List<Cookie> buildBrowserCookies(Map<String, String> cookieMap) {
        List<Cookie> browserCookies = new ArrayList<>();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            browserCookies.add(new Cookie(name, value).setDomain(GOOFISH_COOKIE_DOMAIN).setPath("/"));
            browserCookies.add(new Cookie(name, value).setDomain(TAOBAO_COOKIE_DOMAIN).setPath("/"));
        }
        return browserCookies;
    }

    private String findUpdatedX5sec(List<Cookie> browserCookies, String initialX5sec) {
        for (Cookie cookie : browserCookies) {
            if ("x5sec".equals(cookie.name) && cookie.value != null && !cookie.value.isBlank()
                    && !Objects.equals(cookie.value, initialX5sec)) {
                return cookie.value;
            }
        }
        return null;
    }

    private String captureScreenshot(Page page) {
        byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(screenshot);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduleExpiredSessionCleanup() {
        if (!captchaExecutor.isShutdown()) {
            captchaExecutor.execute(this::cleanupExpiredSessions);
        }
    }

    private void cleanupExpiredSessions() {
        long expireBefore = System.currentTimeMillis() - SESSION_TTL_MS;
        List<String> expiredSessionIds = sessions.values().stream()
                .filter(session -> session.lastAccessTime() < expireBefore)
                .map(CaptchaSession::sessionId)
                .toList();
        expiredSessionIds.forEach(this::closeSessionInternal);
    }

    private void closeSessionInternal(String sessionId) {
        CaptchaSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        accountSessions.remove(session.accountId(), sessionId);
        closeContext(session.context());
        log.info("【账号{}】服务器滑块验证会话已释放: sessionId={}", session.accountId(), sessionId);
    }

    private void closeContext(BrowserContext context) {
        try {
            context.close();
        } catch (Exception e) {
            log.debug("关闭滑块验证浏览器会话失败: {}", e.getMessage());
        }
    }

    private <T> T execute(Callable<T> task, int timeoutSeconds) {
        Future<T> future = captchaExecutor.submit(task);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("滑块验证已中断", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("滑块验证处理超时，请重试", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("滑块验证处理失败", cause);
        }
    }

    @PreDestroy
    void destroy() {
        if (!captchaExecutor.isShutdown()) {
            Future<?> closeTask = captchaExecutor.submit(() ->
                    List.copyOf(sessions.keySet()).forEach(this::closeSessionInternal));
            try {
                closeTask.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                closeTask.cancel(true);
            }
        }
        captchaExecutor.shutdownNow();
    }

    private record CaptchaSession(
            String sessionId,
            Long accountId,
            BrowserContext context,
            Page page,
            String initialX5sec,
            long lastAccessTime) {

        private CaptchaSession touch() {
            return new CaptchaSession(sessionId, accountId, context, page, initialX5sec, System.currentTimeMillis());
        }
    }
}
