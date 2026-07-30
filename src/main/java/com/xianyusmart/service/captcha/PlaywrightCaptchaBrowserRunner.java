package com.xianyusmart.service.captcha;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyusmart.service.CaptchaSolveService;
import com.xianyusmart.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Java Playwright滑块浏览器执行器
 */
@Slf4j
@Component
public class PlaywrightCaptchaBrowserRunner implements CaptchaBrowserRunner {

    private static final long TASK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_AUTO_ATTEMPTS = 5;
    private final Map<Long, BrowserProcessSession> activeBrowserSessions = new ConcurrentHashMap<>();
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final List<String> COOKIE_URLS = List.of(
            "https://www.goofish.com/im",
            "https://passport.goofish.com",
            "https://h5api.m.goofish.com");
    private static final List<SelectorPair> SLIDER_SELECTORS = List.of(
            new SelectorPair(".nc_scale", ".btn_slide"),
            new SelectorPair(".nc_scale", "[id^='nc_'][id$='_n1z']"),
            new SelectorPair("#nc_1_wrapper", "#nc_1_n1z"),
            new SelectorPair(".baxia-slider-track", ".baxia-slider-btn"),
            new SelectorPair("[class*='slider-track']",
                    "[class*='slider-button'], [class*='slider-btn'], [class*='slider-handle']"));
    private static final List<String> SUCCESS_SELECTORS = List.of(
            ".nc-lang-cnt[data-nc-lang='_yesTEXT']",
            "[class*='verify-success']",
            "text=验证通过");
    private static final String FINGERPRINT_SCRIPT = """
            (() => {
              const define = (target, name, value) => {
                try {
                  Object.defineProperty(target, name, {
                    configurable: true,
                    get: () => value
                  });
                } catch (ignored) {
                }
              };
              define(Navigator.prototype, 'webdriver', undefined);
              define(Navigator.prototype, 'plugins', [1, 2, 3, 4, 5]);
              define(Navigator.prototype, 'languages', ['zh-CN', 'zh']);
              define(Navigator.prototype, 'hardwareConcurrency', 8);
              define(Navigator.prototype, 'deviceMemory', 8);
              if (!window.chrome) {
                Object.defineProperty(window, 'chrome', {
                  configurable: true,
                  value: { runtime: {} }
                });
              } else if (!window.chrome.runtime) {
                window.chrome.runtime = {};
              }
              if (navigator.permissions && navigator.permissions.query) {
                const originalQuery = navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query = parameters => {
                  if (parameters && parameters.name === 'notifications') {
                    return Promise.resolve({ state: Notification.permission });
                  }
                  return originalQuery(parameters);
                };
              }
              const patchWebGL = prototype => {
                if (!prototype || !prototype.getParameter) {
                  return;
                }
                const originalGetParameter = prototype.getParameter;
                prototype.getParameter = function(parameter) {
                  if (parameter === 37445) {
                    return 'Intel Inc.';
                  }
                  if (parameter === 37446) {
                    return 'Intel Iris OpenGL Engine';
                  }
                  return originalGetParameter.call(this, parameter);
                };
              };
              patchWebGL(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
              patchWebGL(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);
              try {
                delete window.__playwright__binding__;
                delete window.__pwInitScripts;
                delete window.__webdriver_script_fn;
                delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
              } catch (ignored) {
              }
            })();
            """;

    @Override
    public RunResult run(Long accountId, CaptchaSolveService.Mode mode,
                         String captchaUrl, String cookieText,
                         Consumer<ProgressUpdate> progress) {
        reportProgress(progress, "CHECKING_ENVIRONMENT", "正在检查浏览器运行环境", 0);
        if (!isAllowedCaptchaUrl(captchaUrl)) {
            return new RunResult(Outcome.FAILED, null, "验证地址不受支持");
        }
        if (mode == CaptchaSolveService.Mode.MANUAL_BROWSER && !hasInteractiveDesktop()) {
            return new RunResult(Outcome.UNSUPPORTED, null,
                    "当前部署环境无法显示浏览器，请改用粘贴Cookie");
        }

        boolean automatic = mode == CaptchaSolveService.Mode.AUTO;
        BrowserProcessSession processSession = new BrowserProcessSession();
        if (activeBrowserSessions.putIfAbsent(accountId, processSession) != null) {
            return new RunResult(Outcome.FAILED, null, "该账号已有浏览器验证任务");
        }
        if (Thread.currentThread().isInterrupted()) {
            activeBrowserSessions.remove(accountId, processSession);
            return new RunResult(Outcome.FAILED, null, "滑块验证已取消");
        }
        reportProgress(progress, "STARTING_BROWSER", "正在启动浏览器", 0);
        try (Playwright playwright = Playwright.create();
             Browser browser = chromiumAfterProcessAttached(playwright, processSession).launch(
                     new BrowserType.LaunchOptions()
                             .setHeadless(automatic)
                             .setArgs(List.of(
                                     "--disable-blink-features=AutomationControlled",
                                     "--disable-infobars",
                                     "--disable-dev-shm-usage")));
             BrowserContext context = browser.newContext(
                     new Browser.NewContextOptions()
                             .setUserAgent(USER_AGENT)
                             .setLocale("zh-CN")
                             .setTimezoneId("Asia/Shanghai")
                             .setViewportSize(1365, 768))) {
            context.setDefaultTimeout(10_000);
            if (automatic) {
                // 自动模式在页面脚本执行前统一浏览器指纹，避免同一上下文暴露互相矛盾的特征。
                applyFingerprint(context);
            }
            context.addCookies(buildBrowserCookies(cookieText));

            reportProgress(progress, "OPENING_PAGE", "正在打开滑块验证页面", 0);
            Page page = context.newPage();
            page.navigate(captchaUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(45_000));
            if (!isAllowedCaptchaUrl(page.url())) {
                return new RunResult(Outcome.FAILED, null, "验证页面跳转地址不受支持");
            }

            long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MS;
            RunResult verificationResult = automatic
                    ? runAutomatic(page, deadline, progress)
                    : waitForManual(page, deadline, progress);
            if (verificationResult.outcome() != Outcome.SOLVED) {
                return verificationResult;
            }

            reportProgress(progress, "COLLECTING_COOKIE", "正在回收更新后的Cookie", 0);
            page.waitForTimeout(800);
            String refreshedCookie = buildCookieText(context.cookies(COOKIE_URLS), cookieText);
            if (refreshedCookie.isBlank()) {
                return new RunResult(Outcome.FAILED, null, "验证完成但浏览器未返回Cookie");
            }
            return new RunResult(Outcome.SOLVED, refreshedCookie, "滑块验证完成");
        } catch (Exception e) {
            log.warn("【账号{}】浏览器滑块验证失败: {}", accountId,
                    e.getClass().getSimpleName());
            if (!automatic && isDisplayFailure(e)) {
                return new RunResult(Outcome.UNSUPPORTED, null,
                        "浏览器窗口无法显示，请改用粘贴Cookie");
            }
            return new RunResult(Outcome.FAILED, null, "浏览器滑块验证失败");
        } finally {
            activeBrowserSessions.remove(accountId, processSession);
        }
    }

    @Override
    public void cancel(Long accountId) {
        BrowserProcessSession processSession = activeBrowserSessions.get(accountId);
        if (processSession != null) {
            processSession.cancel();
        }
    }

    private BrowserType chromiumAfterProcessAttached(
            Playwright playwright, BrowserProcessSession processSession) {
        processSession.attach(playwright);
        return playwright.chromium();
    }

    void applyFingerprint(BrowserContext context) {
        context.addInitScript(FINGERPRINT_SCRIPT);
    }

    SliderTarget findSlider(Page page) {
        for (Frame frame : page.frames()) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                for (SelectorPair selectors : SLIDER_SELECTORS) {
                    ElementHandle track = frame.querySelector(selectors.track());
                    ElementHandle handle = frame.querySelector(selectors.handle());
                    if (track != null && handle != null
                            && track.isVisible() && handle.isVisible()
                            && track.boundingBox() != null && handle.boundingBox() != null) {
                        return new SliderTarget(track, handle);
                    }
                }
            } catch (Exception ignored) {
                // iframe刷新期间继续检查其余上下文。
            }
        }
        return null;
    }

    static boolean isAllowedCaptchaUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return isDomain(host, "goofish.com") || isDomain(host, "taobao.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static double calculateDistance(double trackWidth, double handleWidth) {
        return Math.max(180, Math.min(360, trackWidth - handleWidth));
    }

    private static boolean isDomain(String host, String rootDomain) {
        return host.equals(rootDomain) || host.endsWith("." + rootDomain);
    }

    RunResult runAutomatic(Page page, long deadline, Consumer<ProgressUpdate> progress) {
        boolean captchaSeen = false;
        for (int attempt = 1; attempt <= MAX_AUTO_ATTEMPTS
                && System.currentTimeMillis() < deadline; attempt++) {
            reportProgress(progress, "FINDING_SLIDER",
                    "第" + attempt + "次：正在识别滑块", attempt);
            SliderTarget target = waitForSlider(page, Math.min(deadline, System.currentTimeMillis() + 12_000));
            if (target == null) {
                if (hasSuccessSignal(page) || (captchaSeen && !isCaptchaVisible(page))) {
                    return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
                }
                return new RunResult(Outcome.FAILED, null, "未识别到可拖动滑块");
            }
            captchaSeen = true;

            reportProgress(progress, "DRAGGING_SLIDER",
                    "第" + attempt + "次：正在拖动滑块", attempt);
            if (!dragSlider(page, target, attempt)) {
                page.waitForTimeout(500);
                continue;
            }
            reportProgress(progress, "WAITING_RESULT",
                    "第" + attempt + "次：正在等待验证结果", attempt);
            if (waitForCaptchaGone(page, Math.min(deadline, System.currentTimeMillis() + 10_000))) {
                return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
            }
            page.waitForTimeout(ThreadLocalRandom.current().nextInt(700, 1_301));
        }
        if (System.currentTimeMillis() >= deadline) {
            return new RunResult(Outcome.TIMEOUT, null, "滑块验证超时");
        }
        return new RunResult(Outcome.FAILED, null, "自动拖动未通过验证");
    }

    private RunResult waitForManual(Page page, long deadline, Consumer<ProgressUpdate> progress) {
        reportProgress(progress, "WAITING_MANUAL", "浏览器已打开，请人工完成滑块", 0);
        boolean captchaSeen = false;
        while (System.currentTimeMillis() < deadline) {
            SliderTarget target = findSlider(page);
            if (target != null) {
                captchaSeen = true;
            } else if (captchaSeen || hasSuccessSignal(page)) {
                return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
            }
            page.waitForTimeout(500);
        }
        return new RunResult(Outcome.TIMEOUT, null, "人工滑块验证超时");
    }

    private void reportProgress(Consumer<ProgressUpdate> progress, String phase,
                                String message, int attempt) {
        if (progress != null) {
            progress.accept(new ProgressUpdate(phase, message, attempt, MAX_AUTO_ATTEMPTS));
        }
    }

    private SliderTarget waitForSlider(Page page, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            SliderTarget target = findSlider(page);
            if (target != null) {
                return target;
            }
            if (hasSuccessSignal(page)) {
                return null;
            }
            page.waitForTimeout(250);
        }
        return null;
    }

    private boolean dragSlider(Page page, SliderTarget target, int attempt) {
        BoundingBox trackBox = target.track().boundingBox();
        BoundingBox handleBox = target.handle().boundingBox();
        if (trackBox == null || handleBox == null) {
            return false;
        }

        double startX = handleBox.x + handleBox.width / 2;
        double startY = handleBox.y + handleBox.height / 2;
        double distance = calculateDistance(trackBox.width, handleBox.width);
        double overshoot = ThreadLocalRandom.current().nextDouble(3, 8);
        int steps = ThreadLocalRandom.current().nextInt(28, 39);

        page.mouse().move(startX, startY);
        page.waitForTimeout(ThreadLocalRandom.current().nextInt(80, 181));
        page.mouse().down();
        for (int index = 1; index <= steps; index++) {
            double progress = (double) index / steps;
            double eased = progress < 0.5
                    ? 2 * progress * progress
                    : 1 - Math.pow(-2 * progress + 2, 2) / 2;
            double x = startX + (distance + overshoot) * eased;
            double jitter = Math.sin(progress * Math.PI * (2 + attempt % 2))
                    * ThreadLocalRandom.current().nextDouble(0.4, 1.8);
            double y = startY + jitter;
            page.mouse().move(x, y);
            if (index == steps / 2) {
                page.mouse().move(x - ThreadLocalRandom.current().nextDouble(1, 3), y);
            }
            if (index % 8 == 0) {
                page.waitForTimeout(ThreadLocalRandom.current().nextInt(12, 41));
            }
        }
        page.waitForTimeout(ThreadLocalRandom.current().nextInt(50, 121));
        page.mouse().move(startX + distance, startY + ThreadLocalRandom.current().nextDouble(-0.8, 0.8));
        page.waitForTimeout(ThreadLocalRandom.current().nextInt(30, 91));
        page.mouse().up();
        return true;
    }

    private boolean waitForCaptchaGone(Page page, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            if (!isCaptchaVisible(page) || hasSuccessSignal(page)) {
                return true;
            }
            page.waitForTimeout(300);
        }
        return false;
    }

    private boolean isCaptchaVisible(Page page) {
        return findSlider(page) != null;
    }

    boolean hasSuccessSignal(Page page) {
        for (Frame frame : page.frames()) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                for (String selector : SUCCESS_SELECTORS) {
                    ElementHandle success = frame.querySelector(selector);
                    if (success != null && success.isVisible()) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 页面切换时等待下一轮检查。
            }
        }
        return false;
    }

    private List<Cookie> buildBrowserCookies(String cookieText) {
        Map<String, String> cookieMap = XianyuSignUtils.parseCookies(cookieText);
        List<Cookie> cookies = new ArrayList<>();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            cookies.add(new Cookie(entry.getKey(), entry.getValue())
                    .setDomain(".goofish.com").setPath("/"));
            cookies.add(new Cookie(entry.getKey(), entry.getValue())
                    .setDomain(".taobao.com").setPath("/"));
        }
        return cookies;
    }

    private String buildCookieText(List<Cookie> cookies, String originalCookieText) {
        Map<String, String> originalCookieMap = XianyuSignUtils.parseCookies(originalCookieText);
        Map<String, String> cookieMap = new LinkedHashMap<>(originalCookieMap);
        for (Cookie cookie : cookies) {
            if (cookie.name == null || cookie.name.isBlank()
                    || cookie.value == null || cookie.value.isBlank()
                    || cookie.domain == null
                    || !isDomain(cookie.domain.replaceFirst("^\\.", ""), "goofish.com")) {
                continue;
            }
            String originalValue = originalCookieMap.get(cookie.name);
            String selectedValue = cookieMap.get(cookie.name);
            // 浏览器产生的新值优先，避免旧域同名Cookie覆盖滑块验证结果。
            if (originalValue == null || !cookie.value.equals(originalValue) || selectedValue == null) {
                cookieMap.put(cookie.name, cookie.value);
            }
        }
        return XianyuSignUtils.formatCookies(cookieMap);
    }

    private boolean hasInteractiveDesktop() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return true;
        }
        return hasValue(System.getenv("DISPLAY")) || hasValue(System.getenv("WAYLAND_DISPLAY"));
    }

    private boolean isDisplayFailure(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("display")
                || normalized.contains("headed browser")
                || normalized.contains("xserver");
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static final class BrowserProcessSession {

        private boolean cancelled;
        private ProcessHandle driverProcess;

        private synchronized void attach(Playwright playwright) {
            driverProcess = extractDriverProcess(playwright);
            if (cancelled) {
                terminateProcessTree(driverProcess);
                throw new CancellationException("滑块验证已取消");
            }
        }

        private synchronized void cancel() {
            cancelled = true;
            if (driverProcess != null) {
                terminateProcessTree(driverProcess);
            }
        }

        private static ProcessHandle extractDriverProcess(Playwright playwright) {
            try {
                Field field = playwright.getClass().getDeclaredField("driverProcess");
                if (!field.trySetAccessible()) {
                    throw new IllegalStateException("无法访问Playwright驱动进程");
                }
                Process process = (Process) field.get(playwright);
                if (process == null) {
                    throw new IllegalStateException("Playwright驱动进程未启动");
                }
                return process.toHandle();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法绑定Playwright驱动进程", e);
            }
        }

        private static void terminateProcessTree(ProcessHandle root) {
            List<ProcessHandle> descendants = root.descendants().toList();
            for (int index = descendants.size() - 1; index >= 0; index--) {
                ProcessHandle process = descendants.get(index);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            // 先终止浏览器子进程，再终止当前任务的驱动进程，避免残留进程。
            if (root.isAlive()) {
                root.destroyForcibly();
            }
        }
    }

    record SliderTarget(ElementHandle track, ElementHandle handle) {
    }

    private record SelectorPair(String track, String handle) {
    }
}
