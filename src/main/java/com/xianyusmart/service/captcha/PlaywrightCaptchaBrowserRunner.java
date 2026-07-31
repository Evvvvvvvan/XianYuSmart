package com.xianyusmart.service.captcha;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Mouse;
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
import java.nio.file.Path;
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
    private static final String GOOFISH_HOME_URL = "https://www.goofish.com";
    private static final String COOKIE_EXPIRED_MESSAGE =
            "Cookie Session已过期，请重新扫码登录后再连接";
    private final Map<Long, BrowserProcessSession> activeBrowserSessions = new ConcurrentHashMap<>();
    private static final String USER_AGENT_TEMPLATE =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36";
    private static final List<String> COOKIE_URLS = List.of(
            "https://www.goofish.com/im",
            "https://passport.goofish.com",
            "https://h5api.m.goofish.com");
    private static final List<String> SLIDER_HANDLE_SELECTORS = List.of(
            "#nc_1_n1z",
            ".btn_slide",
            ".nc_iconfont",
            ".slide-btn",
            "#nc_1_n1t",
            "[data-role='slider']",
            "#nc_1_n1z .icon",
            ".btn_slide > i",
            "#aliyunCaptcha-sliding-slider",
            "#nc_1_n1z[style]",
            ".J_MIDDLEWARE_FRAME .btn_slide",
            "span.nc_iconfont",
            "#baxia-dialog .btn_slide",
            "div[role='button'][class*='slide']",
            "div[draggable='true'][class*='slide']",
            "[class*='slider-button'], [class*='slider-btn'], [class*='slider-handle']");
    private static final List<String> SLIDER_TRACK_SELECTORS = List.of(
            ".nc_scale",
            ".scale_text",
            ".slide-track",
            "#nc_1__scale",
            ".nc-lang",
            ".nc_wrapper",
            ".slide-verify-track",
            ".slider-track",
            ".baxia-slider-track",
            "[class*='track']");
    // 仅匹配滑块状态类，避免把静态成功文案误判为已通过。
    private static final List<String> SUCCESS_SELECTORS = List.of(
            ".nc_ok",
            "#nc_1_n1z.success",
            ".nc_wrapper .icon-success",
            "#baxia-dialog .icon-success",
            ".slide-verify .verify-success");
    private static final List<String> FAILURE_SELECTORS = List.of(
            ".nc_error",
            ".errloading",
            "#nc_1_refresh1",
            ".fail");
    private static final String MESSAGE_ENTRY_SCRIPT = """
            () => {
              const candidates = Array.from(document.querySelectorAll(
                '[class*="sidebar-item-wrap"], a[href*="/im"], [role="button"]'
              ));
              const target = candidates.find(element => {
                const text = (element.textContent || '').replace(/\\s+/g, '').trim();
                const href = element.getAttribute('href') || '';
                return href.includes('/im') || text === '消息'
                  || (text.includes('消息') && text.length <= 8);
              });
              if (!target) {
                return false;
              }
              target.click();
              return true;
            }
            """;
    private static final String FINGERPRINT_SCRIPT = """
            (() => {
              try {
              try {
                delete Object.getPrototypeOf(navigator).webdriver;
                Object.defineProperty(navigator, 'webdriver', {
                  configurable: true,
                  get: () => undefined
                });
                Object.defineProperty(Navigator.prototype, 'webdriver', {
                  configurable: true,
                  get: () => undefined
                });
              } catch (ignored) {
              }
              if (!window.chrome) {
                window.chrome = {};
              }
              if (!window.chrome.runtime) {
                window.chrome.runtime = {
                  OnInstalledReason: {
                    INSTALL: 'install',
                    UPDATE: 'update',
                    CHROME_UPDATE: 'chrome_update',
                    SHARED_MODULE_UPDATE: 'shared_module_update'
                  },
                  OnRestartRequiredReason: {
                    APP_UPDATE: 'app_update',
                    OS_UPDATE: 'os_update',
                    PERIODIC: 'periodic'
                  },
                  PlatformArch: {
                    ARM: 'arm',
                    X86_32: 'x86-32',
                    X86_64: 'x86-64'
                  },
                  PlatformOs: {
                    MAC: 'mac',
                    WIN: 'win',
                    ANDROID: 'android',
                    CROS: 'cros',
                    LINUX: 'linux',
                    OPENBSD: 'openbsd'
                  }
                };
              }
              if (!window.chrome.csi) {
                window.chrome.csi = () => ({
                  startE: Date.now(),
                  onloadT: Date.now(),
                  pageT: 0,
                  tran: 15
                });
              }
              if (!window.chrome.loadTimes) {
                window.chrome.loadTimes = () => ({
                  commitLoadTime: Date.now() / 1000 - 5,
                  connectionInfo: 'h2',
                  finishDocumentLoadTime: Date.now() / 1000 - 3,
                  finishLoadTime: Date.now() / 1000 - 2,
                  firstPaintAfterLoadTime: 0,
                  firstPaintTime: Date.now() / 1000 - 4,
                  navigationType: 'Other',
                  npnNegotiatedProtocol: 'h2',
                  requestTime: Date.now() / 1000 - 6,
                  startLoadTime: Date.now() / 1000 - 6,
                  wasAlternateProtocolAvailable: false,
                  wasFetchedViaSpdy: true,
                  wasNpnNegotiated: true
                });
              }
              const fakePlugin = (name, filename, description) => {
                const plugin = { name, filename, description, length: 1 };
                plugin[0] = {
                  type: 'application/pdf',
                  suffixes: 'pdf',
                  description: 'Portable Document Format'
                };
                plugin.item = index => plugin[index] || null;
                plugin.namedItem = value => plugin[0] && plugin[0].name === value
                  ? plugin[0] : null;
                return plugin;
              };
              const plugins = [
                fakePlugin('PDF Viewer', 'internal-pdf-viewer', 'Portable Document Format'),
                fakePlugin('Chrome PDF Viewer', 'internal-pdf-viewer', 'Portable Document Format'),
                fakePlugin('Chromium PDF Viewer', 'internal-pdf-viewer', 'Portable Document Format'),
                fakePlugin('Microsoft Edge PDF Viewer', 'internal-pdf-viewer', 'Portable Document Format'),
                fakePlugin('WebKit built-in PDF', 'internal-pdf-viewer', 'Portable Document Format')
              ];
              plugins.item = index => plugins[index] || null;
              plugins.namedItem = name => plugins.find(plugin => plugin.name === name) || null;
              plugins.refresh = () => {};
              Object.defineProperty(navigator, 'plugins', {
                configurable: true,
                get: () => plugins
              });
              Object.defineProperty(navigator, 'languages', {
                configurable: true,
                get: () => ['zh-CN', 'zh', 'en-US', 'en']
              });
              Object.defineProperty(navigator, 'hardwareConcurrency', {
                configurable: true,
                get: () => 8
              });
              Object.defineProperty(navigator, 'deviceMemory', {
                configurable: true,
                get: () => 8
              });
              if (window.Notification && navigator.permissions && navigator.permissions.query) {
                const originalQuery = navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query = parameters => parameters
                  && parameters.name === 'notifications'
                  ? Promise.resolve({ state: Notification.permission, onchange: null })
                  : originalQuery(parameters);
              }
              const patchWebGL = prototype => {
                if (!prototype || !prototype.getParameter) {
                  return;
                }
                const originalGetParameter = prototype.getParameter;
                prototype.getParameter = function(parameter) {
                  if (parameter === 0x9245) {
                    return 'Google Inc. (NVIDIA)';
                  }
                  if (parameter === 0x9246) {
                    return 'ANGLE (NVIDIA, NVIDIA GeForce GTX 1060 Direct3D11 vs_5_0 ps_5_0)';
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
                Object.keys(window)
                  .filter(key => key.startsWith('cdc_'))
                  .forEach(key => delete window[key]);
              } catch (ignored) {
              }
              } catch (ignored) {
              }
            })();
            """;
    private static final String SLIDER_HEURISTIC_SCRIPT = """
            () => {
              const containers = [];
              [
                '#nc_1',
                '.nc_wrapper',
                '#baxia-dialog',
                '.J_MIDDLEWARE_FRAME',
                '.slide-verify',
                '.nc-container',
                '[class*="aliyunCaptcha"]',
                '[class*="captcha"]',
                '[class*="slider"]'
              ].forEach(selector => document.querySelectorAll(selector)
                .forEach(container => containers.push(container)));
              const candidates = [];
              const elements = new Set();
              containers.forEach(container => container
                .querySelectorAll('div, span, a, button, i, em')
                .forEach(element => elements.add(element)));
              elements.forEach(element => {
                const box = element.getBoundingClientRect();
                if (box.width < 24 || box.width > 60 || box.height < 24 || box.height > 60
                    || box.bottom <= 0 || box.right <= 0) {
                  return;
                }
                const style = window.getComputedStyle(element);
                if (style.visibility === 'hidden' || style.display === 'none') {
                  return;
                }
                const draggable = style.cursor === 'pointer' || style.cursor === 'move'
                  || style.cursor === 'grab' || element.draggable
                  || element.getAttribute('draggable') === 'true';
                const text = (element.innerText || '').trim();
                const identity = `${element.id || ''} ${element.className || ''}`;
                if (!draggable || text.length > 5 || /close|refresh|reload/i.test(identity)) {
                  return;
                }
                let score = 5;
                if (Math.abs(box.width - box.height) < 8) {
                  score += 3;
                }
                if (style.position === 'absolute' || style.position === 'relative') {
                  score += 2;
                }
                candidates.push({ element, score });
              });
              candidates.sort((left, right) => right.score - left.score);
              return candidates.length ? candidates[0].element : null;
            }
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
        Playwright playwright = null;
        String failureStage = "启动浏览器";
        try {
            failureStage = "创建浏览器进程";
            playwright = Playwright.create();
            BrowserType browserType = chromiumAfterProcessAttached(playwright, processSession);
            failureStage = "启动浏览器";
            Browser browser = browserType.launch(browserLaunchOptions(browserType, automatic));
            failureStage = "创建浏览器上下文";
            BrowserContext context = browser.newContext(
                     new Browser.NewContextOptions()
                             .setUserAgent(browserUserAgent(browser.version()))
                             .setLocale("zh-CN")
                             .setTimezoneId("Asia/Shanghai")
                             .setViewportSize(1365, 768));
            context.setDefaultTimeout(10_000);
            if (automatic) {
                // 自动模式在页面脚本执行前统一浏览器指纹，避免同一上下文暴露互相矛盾的特征。
                applyFingerprint(context);
            }
            failureStage = "加载账号Cookie";
            context.addCookies(buildBrowserCookies(cookieText));

            reportProgress(progress, "OPENING_PAGE", "正在打开滑块验证页面", 0);
            failureStage = "打开滑块验证页面";
            Page page = context.newPage();
            page.navigate(captchaUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(45_000));
            if (!isAllowedCaptchaUrl(page.url())) {
                return new RunResult(Outcome.FAILED, null, "验证页面跳转地址不受支持");
            }

            long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MS;
            failureStage = automatic ? "执行自动拖动" : "等待人工拖动";
            RunResult verificationResult = automatic
                    ? runAutomatic(context, page, deadline, progress)
                    : waitForManual(page, deadline, progress);
            if (verificationResult.outcome() != Outcome.SOLVED) {
                return verificationResult;
            }

            reportProgress(progress, "COLLECTING_COOKIE", "正在回收更新后的Cookie", 0);
            failureStage = "回收更新后的Cookie";
            List<Page> activePages = context.pages();
            if (!activePages.isEmpty()) {
                activePages.get(activePages.size() - 1).waitForTimeout(800);
            }
            String refreshedCookie = buildCookieText(context.cookies(COOKIE_URLS), cookieText);
            if (refreshedCookie.isBlank()) {
                return new RunResult(Outcome.FAILED, null, "验证完成但浏览器未返回Cookie");
            }
            return new RunResult(Outcome.SOLVED, refreshedCookie, "滑块验证完成");
        } catch (Exception e) {
            String failureMessage = browserFailureMessage(failureStage, e);
            log.warn("【账号{}】浏览器滑块验证失败: type={}, reason={}", accountId,
                    e.getClass().getSimpleName(), failureMessage);
            if (!automatic && isDisplayFailure(e)) {
                return new RunResult(Outcome.UNSUPPORTED, null,
                        "浏览器窗口无法显示，请改用粘贴Cookie");
            }
            return new RunResult(Outcome.FAILED, null, failureMessage);
        } finally {
            // 先终止独立进程再关闭管道，避免关闭阻塞并回收Playwright传输线程。
            processSession.terminate();
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception e) {
                    log.debug("【账号{}】Playwright传输管道关闭失败: {}",
                            accountId, e.getClass().getSimpleName());
                }
            }
            activeBrowserSessions.remove(accountId, processSession);
        }
    }

    static String browserFailureMessage(String stage, Exception exception) {
        String rawMessage = exception == null ? null : exception.getMessage();
        String normalized = rawMessage == null ? "" : rawMessage
                .replaceAll("https?://\\S+", "验证地址")
                .replaceAll("(?i)(cookie|token|authorization)[=:]\\S+", "$1=<redacted>")
                .replaceAll("\\s+", " ")
                .trim();
        String lowerMessage = normalized.toLowerCase(Locale.ROOT);
        String reason;
        if (lowerMessage.contains("net::err_")) {
            reason = "验证页面网络请求失败";
        } else if (lowerMessage.contains("target page")
                && lowerMessage.contains("has been closed")) {
            reason = "验证页面已关闭";
        } else if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            reason = "验证页面响应超时";
        } else if (lowerMessage.contains("executable doesn't exist")) {
            reason = "浏览器运行文件缺失";
        } else if (normalized.isBlank()) {
            reason = exception == null ? "未知异常" : exception.getClass().getSimpleName();
        } else {
            reason = normalized.length() > 160
                    ? normalized.substring(0, 160) + "..."
                    : normalized;
        }
        return stage + "失败：" + reason;
    }

    @Override
    public void cancel(Long accountId) {
        BrowserProcessSession processSession = activeBrowserSessions.get(accountId);
        if (processSession != null) {
            processSession.terminate();
        }
    }

    private BrowserType chromiumAfterProcessAttached(
            Playwright playwright, BrowserProcessSession processSession) {
        processSession.attach(playwright);
        return playwright.chromium();
    }

    private BrowserType.LaunchOptions browserLaunchOptions(BrowserType browserType, boolean automatic) {
        return new BrowserType.LaunchOptions()
                // 显式使用完整Chromium，避免默认headless shell暴露不同的浏览器特征。
                .setExecutablePath(Path.of(browserType.executablePath()))
                .setHeadless(automatic)
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-infobars",
                        "--disable-dev-shm-usage",
                        "--no-first-run",
                        "--no-default-browser-check"));
    }

    private static String browserUserAgent(String browserVersion) {
        String majorVersion = browserVersion == null ? "" : browserVersion.split("\\.", 2)[0];
        if (!majorVersion.matches("\\d+")) {
            majorVersion = "146";
        }
        return USER_AGENT_TEMPLATE.formatted(majorVersion);
    }

    void applyFingerprint(BrowserContext context) {
        context.addInitScript(FINGERPRINT_SCRIPT);
    }

    SliderTarget findSlider(Page page) {
        List<Frame> frames = page.frames();
        // 先检查全部frame的明确标识，避免主页面普通按钮遮蔽验证码iframe。
        for (Frame frame : frames) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                ElementHandle handle = findVisibleElement(frame, SLIDER_HANDLE_SELECTORS);
                if (handle != null) {
                    ElementHandle track = findVisibleElement(frame, SLIDER_TRACK_SELECTORS);
                    return new SliderTarget(track, handle);
                }
            } catch (Exception ignored) {
                // iframe刷新期间继续检查其余上下文。
            }
        }
        for (Frame frame : frames) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                SliderTarget target = findSliderByHeuristic(frame);
                if (target != null) {
                    return target;
                }
            } catch (Exception ignored) {
                // iframe刷新期间继续检查其余上下文。
            }
        }
        return null;
    }

    private ElementHandle findVisibleElement(Frame frame, List<String> selectors) {
        for (String selector : selectors) {
            ElementHandle element = frame.querySelector(selector);
            if (element == null) {
                continue;
            }
            if (element.isVisible() && element.boundingBox() != null) {
                return element;
            }
            element.dispose();
        }
        return null;
    }

    private SliderTarget findSliderByHeuristic(Frame frame) {
        JSHandle result = frame.evaluateHandle(SLIDER_HEURISTIC_SCRIPT);
        ElementHandle element = result.asElement();
        BoundingBox handleBox = element == null ? null : element.boundingBox();
        if (element == null || !element.isVisible() || handleBox == null) {
            result.dispose();
            return null;
        }
        ElementHandle track = findVisibleElement(frame, SLIDER_TRACK_SELECTORS);
        BoundingBox trackBox = track == null ? null : track.boundingBox();
        if (!isRelatedSliderTrack(trackBox, handleBox)) {
            if (track != null) {
                track.dispose();
            }
            result.dispose();
            return null;
        }
        return new SliderTarget(track, element);
    }

    private boolean isRelatedSliderTrack(BoundingBox track, BoundingBox handle) {
        if (track == null || handle == null || track.width < 150) {
            return false;
        }
        double handleCenterX = handle.x + handle.width / 2;
        boolean horizontal = handleCenterX >= track.x - handle.width
                && handleCenterX <= track.x + track.width + handle.width;
        boolean vertical = handle.y < track.y + track.height + handle.height
                && handle.y + handle.height > track.y - handle.height;
        return horizontal && vertical;
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

    RunResult runAutomatic(BrowserContext context, Page page, long deadline,
                           Consumer<ProgressUpdate> progress) {
        boolean captchaSeen = false;
        for (int attempt = 1; attempt <= MAX_AUTO_ATTEMPTS
                && System.currentTimeMillis() < deadline; attempt++) {
            reportProgress(progress, "FINDING_SLIDER",
                    "第" + attempt + "次：正在识别滑块", attempt);
            SliderTarget target = waitForSlider(page, Math.min(deadline, System.currentTimeMillis() + 12_000));
            if (target == null) {
                if (isLoginPage(page)) {
                    return new RunResult(Outcome.FAILED, null, COOKIE_EXPIRED_MESSAGE);
                }
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
                if (isLoginPage(page)) {
                    return new RunResult(Outcome.FAILED, null, COOKIE_EXPIRED_MESSAGE);
                }
                return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
            }
            if (isLoginPage(page)) {
                return new RunResult(Outcome.FAILED, null, COOKIE_EXPIRED_MESSAGE);
            }
            if (attempt < MAX_AUTO_ATTEMPTS) {
                String failureReason = failureReason(page);
                String retryMessage = failureReason == null
                        ? "第" + attempt + "次未通过，正在重置滑块"
                        : "第" + attempt + "次未通过：" + failureReason + "，正在重置滑块";
                reportProgress(progress, "RETRYING_SLIDER",
                        retryMessage, attempt);
                reportProgress(progress, "RESETTING_SESSION",
                        "第" + attempt + "次：正在从首页重新打开消息页", attempt);
                // 同页重试会累积平台失败态，从首页重新进入消息页生成新验证会话。
                ReopenResult reopenResult = reopenFromHome(context, page, deadline);
                if (reopenResult.page() == null) {
                    return new RunResult(Outcome.FAILED, null, reopenResult.message());
                }
                page = reopenResult.page();
                captchaSeen = false;
            }
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
            if (isLoginPage(page)) {
                return new RunResult(Outcome.FAILED, null, COOKIE_EXPIRED_MESSAGE);
            }
            if (hasSuccessSignal(page)) {
                return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
            }
            SliderTarget target = findSlider(page);
            if (target != null) {
                captchaSeen = true;
            } else if (captchaSeen) {
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
            if (hasSuccessSignal(page)) {
                return null;
            }
            SliderTarget target = findSlider(page);
            if (target != null) {
                return target;
            }
            page.waitForTimeout(250);
        }
        return null;
    }

    private boolean dragSlider(Page page, SliderTarget target, int attempt) {
        BoundingBox handleBox = target.handle().boundingBox();
        if (handleBox == null) {
            return false;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double startX = handleBox.x + handleBox.width / 2;
        double startY = handleBox.y + handleBox.height / 2;
        BoundingBox trackBox = target.track() == null ? null : target.track().boundingBox();
        double distance = trackBox == null
                ? 300
                : calculateDistance(trackBox.width, handleBox.width);
        int stepsBase;
        int delayMin;
        int delayMax;
        boolean pauseDuringDrag = false;
        switch (attempt) {
            case 1 -> {
                stepsBase = 30;
                delayMin = 20;
                delayMax = 50;
            }
            case 2 -> {
                stepsBase = 35;
                delayMin = 30;
                delayMax = 70;
            }
            case 3 -> {
                stepsBase = 25;
                delayMin = 15;
                delayMax = 40;
            }
            case 4 -> {
                stepsBase = 40;
                delayMin = 40;
                delayMax = 90;
                pauseDuringDrag = true;
            }
            default -> {
                stepsBase = 30 + random.nextInt(15);
                delayMin = 20 + random.nextInt(30);
                delayMax = delayMin + 30 + random.nextInt(40);
            }
        }
        int steps = stepsBase + random.nextInt(15);
        double actualStartX = startX + random.nextDouble(-4, 4);
        double actualStartY = startY + random.nextDouble(-3, 3);
        double approachAngle = random.nextDouble(0, Math.PI * 2);
        double approachDistance = random.nextDouble(40, 120);
        double approachX = actualStartX + Math.cos(approachAngle) * approachDistance;
        double approachY = actualStartY + Math.sin(approachAngle) * approachDistance;
        Mouse mouse = page.mouse();
        boolean mouseDown = false;
        try {
            // 先自然接近滑块再按下，避免鼠标瞬移到固定中心点。
            mouse.move(approachX, approachY, new Mouse.MoveOptions().setSteps(8));
            page.waitForTimeout(random.nextInt(80, 201));
            int approachSteps = random.nextInt(3, 6);
            for (int index = 1; index <= approachSteps; index++) {
                double progress = (double) index / approachSteps;
                double eased = progress * progress * (3 - 2 * progress);
                mouse.move(
                        approachX + (actualStartX - approachX) * eased,
                        approachY + (actualStartY - approachY) * eased,
                        new Mouse.MoveOptions().setSteps(5));
                page.waitForTimeout(random.nextInt(12, 38));
            }
            page.waitForTimeout(random.nextInt(100, 251));
            mouse.down();
            mouseDown = true;
            page.waitForTimeout(random.nextInt(80, 181));
            mouse.move(
                    actualStartX + random.nextDouble(-1.5, 1.5),
                    actualStartY + random.nextDouble(-1.5, 1.5),
                    new Mouse.MoveOptions().setSteps(3));
            page.waitForTimeout(random.nextInt(30, 81));

            double pausePoint = random.nextDouble(0.3, 0.7);
            boolean paused = false;
            double lastX = actualStartX;
            double lastY = actualStartY;
            double arcDirection = random.nextBoolean() ? -1 : 1;
            double arcAmplitude = random.nextDouble(3, 8);
            for (int index = 1; index <= steps; index++) {
                double progress = (double) index / steps;
                double speedWeight;
                if (progress < 0.2) {
                    speedWeight = 1 - 0.7 * (progress / 0.2);
                } else if (progress < 0.7) {
                    speedWeight = 0.25 + 0.15 * Math.sin(progress * Math.PI * 4);
                } else {
                    speedWeight = 0.4 + 0.6 * ((progress - 0.7) / 0.3);
                }
                double poweredProgress = Math.pow(progress, 2.5);
                double poweredRemaining = Math.pow(1 - progress, 2.5);
                double eased = poweredProgress / (poweredProgress + poweredRemaining);
                double x = actualStartX + distance * eased;
                if (random.nextDouble() < 0.05 && index > 3 && index < steps - 3) {
                    x = lastX - random.nextDouble(2, 5);
                }
                double arcOffset = arcDirection * arcAmplitude * Math.sin(Math.PI * progress);
                double targetY = actualStartY + arcOffset;
                double y = (lastY + random.nextDouble(-3, 3)) * 0.6 + targetY * 0.4;
                mouse.move(x, y, new Mouse.MoveOptions().setSteps(3));
                double medianDelay = delayMin + (delayMax - delayMin) * 0.4;
                double logNormalDelay = medianDelay * Math.exp(0.5 * random.nextGaussian());
                double baseDelay = Math.max(delayMin, Math.min(delayMax * 2, logNormalDelay));
                page.waitForTimeout(Math.max(8, baseDelay * speedWeight));
                lastX = x;
                lastY = y;
                if (pauseDuringDrag && !paused && progress >= pausePoint) {
                    page.waitForTimeout(300);
                    paused = true;
                }
            }

            double overshoot = random.nextDouble(5, 13);
            page.waitForTimeout(random.nextInt(30, 101));
            mouse.move(
                    actualStartX + distance + overshoot,
                    actualStartY + random.nextDouble(-5, 5),
                    new Mouse.MoveOptions().setSteps(4));
            page.waitForTimeout(random.nextInt(50, 131));
            mouse.move(
                    actualStartX + distance,
                    actualStartY + random.nextDouble(-3, 3),
                    new Mouse.MoveOptions().setSteps(4));
            int adjustments = random.nextDouble() < 0.7 ? 1 : 2;
            for (int index = 0; index < adjustments; index++) {
                page.waitForTimeout(random.nextInt(40, 101));
                mouse.move(
                        actualStartX + distance + random.nextDouble(-2, 2),
                        actualStartY + random.nextDouble(-2, 2),
                        new Mouse.MoveOptions().setSteps(2));
            }
            page.waitForTimeout(random.nextInt(50, 121));
            mouse.up();
            mouseDown = false;
            return true;
        } finally {
            if (mouseDown) {
                mouse.up();
            }
        }
    }

    private ReopenResult reopenFromHome(BrowserContext context, Page currentPage, long deadline) {
        try {
            try {
                currentPage.evaluate("""
                        () => {
                          try { localStorage.clear(); } catch (ignored) {}
                          try { sessionStorage.clear(); } catch (ignored) {}
                        }
                        """);
            } catch (Exception ignored) {
                // 页面正在切换时直接关闭旧会话。
            }
            currentPage.close();

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return new ReopenResult(null, "重新打开消息页超时");
            }
            int navigationTimeout = (int) Math.max(1,
                    Math.min(45_000, remaining));
            Page homePage = context.newPage();
            homePage.navigate(GOOFISH_HOME_URL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeout));
            if (!waitWithinDeadline(homePage, deadline, 2_000)) {
                return new ReopenResult(null, "重新打开消息页超时");
            }
            if (isLoginPage(homePage)) {
                return new ReopenResult(null, COOKIE_EXPIRED_MESSAGE);
            }

            List<Page> pagesBeforeClick = new ArrayList<>(context.pages());
            if (!clickMessageEntry(homePage)) {
                return new ReopenResult(null, "未找到闲鱼消息入口，请稍后重试");
            }
            long popupDeadline = Math.min(deadline, System.currentTimeMillis() + 8_000);
            while (System.currentTimeMillis() < popupDeadline) {
                for (Page candidate : context.pages()) {
                    if (pagesBeforeClick.contains(candidate) || candidate.isClosed()) {
                        continue;
                    }
                    if (!waitWithinDeadline(candidate, deadline, 1_500)) {
                        return new ReopenResult(null, "重新打开消息页超时");
                    }
                    if (isLoginPage(candidate)) {
                        return new ReopenResult(null, COOKIE_EXPIRED_MESSAGE);
                    }
                    if (isMessagePageUrl(candidate.url())) {
                        homePage.close();
                        return new ReopenResult(candidate, null);
                    }
                    candidate.close();
                }
                if (isMessagePageUrl(homePage.url())) {
                    if (!waitWithinDeadline(homePage, deadline, 1_500)) {
                        return new ReopenResult(null, "重新打开消息页超时");
                    }
                    return isLoginPage(homePage)
                            ? new ReopenResult(null, COOKIE_EXPIRED_MESSAGE)
                            : new ReopenResult(homePage, null);
                }
                if (!waitWithinDeadline(homePage, popupDeadline, 250)) {
                    break;
                }
            }
            return new ReopenResult(null, "点击消息入口后未打开消息页，请稍后重试");
        } catch (Exception e) {
            return new ReopenResult(null, "重新打开消息页失败，请稍后重试");
        }
    }

    private boolean waitWithinDeadline(Page page, long deadline, long maximumWait) {
        long wait = Math.min(maximumWait, deadline - System.currentTimeMillis());
        if (wait <= 0) {
            return false;
        }
        page.waitForTimeout(wait);
        return System.currentTimeMillis() < deadline;
    }

    private boolean clickMessageEntry(Page homePage) {
        return Boolean.TRUE.equals(homePage.evaluate(MESSAGE_ENTRY_SCRIPT));
    }

    static boolean isMessagePageUrl(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            return uri.getHost() != null
                    && isDomain(uri.getHost().toLowerCase(Locale.ROOT), "goofish.com")
                    && ("/im".equals(path) || (path != null && path.startsWith("/im/")));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isLoginPage(Page page) {
        String url = page.url().toLowerCase(Locale.ROOT);
        if (url.contains("login.taobao.com") || url.contains("login.goofish.com")
                || url.matches(".*(?:/login|/uilogin)(?:[/?#].*)?$")) {
            return true;
        }
        try {
            String bodyText = (String) page.evaluate(
                    "() => document.body ? document.body.innerText : ''");
            return isLoginBody(bodyText);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isLoginBody(String bodyText) {
        return bodyText != null
                && bodyText.matches("(?s).*(扫码登录|手机号登录|账号密码登录).*");
    }

    private boolean waitForCaptchaGone(Page page, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            if (hasSuccessSignal(page)) {
                return true;
            }
            if (hasFailureSignal(page)) {
                return false;
            }
            if (!isCaptchaVisible(page)) {
                return !isLoginPage(page);
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

    private boolean hasFailureSignal(Page page) {
        return failureReason(page) != null;
    }

    private String failureReason(Page page) {
        for (Frame frame : page.frames()) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                for (String selector : FAILURE_SELECTORS) {
                    ElementHandle failure = frame.querySelector(selector);
                    if (failure != null && failure.isVisible()) {
                        String text = failure.innerText();
                        if (text == null || text.isBlank()) {
                            return "平台返回验证失败";
                        }
                        String normalized = text.replaceAll("\\s+", " ").trim();
                        return normalized.length() > 120
                                ? normalized.substring(0, 120) + "..."
                                : normalized;
                    }
                }
            } catch (Exception ignored) {
                // 页面切换时等待下一轮检查。
            }
        }
        return null;
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

    private record ReopenResult(Page page, String message) {
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

        private synchronized void terminate() {
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

}
