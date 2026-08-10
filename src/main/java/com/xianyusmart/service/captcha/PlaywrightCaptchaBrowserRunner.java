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
import com.microsoft.playwright.options.ScreenshotType;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Java Playwright滑块浏览器执行器
 */
@Slf4j
@Component
public class PlaywrightCaptchaBrowserRunner implements CaptchaBrowserRunner {

    private static final long TASK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_AUTO_ATTEMPTS = 5;
    private static final int VIEWPORT_WIDTH = 1365;
    private static final int VIEWPORT_HEIGHT = 768;
    private static final long MANUAL_FRAME_INTERVAL_MS = 700;
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
    private static final List<String> RISK_COOKIE_NAMES = List.of(
            "x5secdata", "x5sec", "x5sectag", "x5pref",
            "bx-cookie-test", "tfstk", "cbc", "sca", "isg");
    private static final List<String> SLIDER_HANDLE_SELECTORS = List.of(
            "#nc_1_n1z",
            ".btn_slide",
            ".nc_iconfont",
            ".slide-btn",
            "#nc_1_n1t",
            ".nc-lang-cnt",
            "[data-role='slider']",
            "#nc_1_n1z .icon",
            ".btn_slide > i",
            "#aliyunCaptcha-sliding-slider",
            "#scratch-captcha-btn",
            ".scratch-captcha-slider .button",
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
            ".scratch-captcha-slider",
            "#nc_1_n1t",
            "[class*='scale']",
            "[class*='track']");
    private static final List<String> CAPTCHA_CONTAINER_SELECTORS = List.of(
            "#nc_1_wrapper",
            "#nc_1",
            "#nocaptcha",
            ".nc-container",
            ".nc_wrapper",
            "#baxia-dialog",
            ".J_MIDDLEWARE_FRAME",
            "#scratch-captcha",
            ".scratch-captcha-slider",
            "[class*='captcha']");
    private static final List<String> SCRATCH_CAPTCHA_SELECTORS = List.of(
            "#scratch-captcha",
            "#scratch-captcha-btn",
            ".scratch-captcha-slider");
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
                  get: () => false
                });
                Object.defineProperty(Navigator.prototype, 'webdriver', {
                  configurable: true,
                  get: () => false
                });
              } catch (ignored) {
              }
              try {
                Object.defineProperty(Navigator.prototype, 'platform', {
                  configurable: true,
                  get: () => 'Win32'
                });
                Object.defineProperty(navigator, 'platform', {
                  configurable: true,
                  get: () => 'Win32'
                });
                Object.defineProperty(navigator, 'vendor', {
                  configurable: true,
                  get: () => 'Google Inc.'
                });
                Object.defineProperty(navigator, 'appVersion', {
                  configurable: true,
                  get: () => navigator.userAgent.startsWith('Mozilla/')
                    ? navigator.userAgent.substring(8) : navigator.userAgent
                });
                const chromeVersion = (navigator.userAgent.split('Chrome/')[1] || '146.0.0.0')
                  .split(' ')[0];
                const majorVersion = chromeVersion.split('.')[0];
                const userAgentData = {
                  brands: [
                    { brand: 'Google Chrome', version: majorVersion },
                    { brand: 'Chromium', version: majorVersion },
                    { brand: 'Not.A/Brand', version: '8' }
                  ],
                  mobile: false,
                  platform: 'Windows',
                  getHighEntropyValues: () => Promise.resolve({
                    architecture: 'x86',
                    bitness: '64',
                    brands: userAgentData.brands,
                    fullVersionList: userAgentData.brands,
                    mobile: false,
                    model: '',
                    platform: 'Windows',
                    platformVersion: '15.0.0',
                    uaFullVersion: chromeVersion,
                    wow64: false
                  }),
                  toJSON: () => ({
                    brands: userAgentData.brands,
                    mobile: false,
                    platform: 'Windows'
                  })
                };
                Object.defineProperty(navigator, 'userAgentData', {
                  configurable: true,
                  get: () => userAgentData
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
                const originalToDataUrl = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = function(...args) {
                  const context = this.getContext('2d');
                  if (context && this.width > 0 && this.height > 0) {
                    try {
                      const image = context.getImageData(0, 0, this.width, this.height);
                      for (let index = 0; index < image.data.length; index += 4) {
                        if (Math.random() < 0.03) {
                          image.data[index] = (image.data[index]
                            + (Math.random() < 0.5 ? -1 : 1)) & 0xff;
                        }
                      }
                      context.putImageData(image, 0, 0);
                    } catch (ignored) {
                    }
                  }
                  return originalToDataUrl.apply(this, args);
                };
              } catch (ignored) {
              }
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

        boolean automatic = mode == CaptchaSolveService.Mode.AUTO;
        boolean headless = automatic || !hasInteractiveDesktop();
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
            Browser browser = browserType.launch(browserLaunchOptions(browserType, headless));
            failureStage = "创建浏览器上下文";
            BrowserContext context = browser.newContext(
                     new Browser.NewContextOptions()
                             .setUserAgent(browserUserAgent(browser.version()))
                             .setLocale("zh-CN")
                             .setTimezoneId("Asia/Shanghai")
                             .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT));
            context.setDefaultTimeout(10_000);
            // 自动和人工模式使用同一浏览器指纹，避免有头模式暴露自动化特征。
            applyFingerprint(context);
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
            if (pageShowsLoadFailure(page)) {
                return new RunResult(Outcome.FAILED, null, "验证页面加载失败，请稍后重试");
            }

            long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MS;
            failureStage = automatic ? "执行自动拖动" : "等待人工拖动";
            RunResult verificationResult = automatic
                    ? runAutomatic(context, page, deadline, progress)
                    : waitForManual(page, processSession, deadline, progress);
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

    @Override
    public CaptchaSolveService.ManualFrame getManualFrame(Long accountId) {
        BrowserProcessSession processSession = activeBrowserSessions.get(accountId);
        return processSession == null ? null : processSession.manualFrame;
    }

    @Override
    public void submitManualDrag(Long accountId, CaptchaSolveService.ManualDrag drag) {
        validateManualDrag(drag);
        BrowserProcessSession processSession = activeBrowserSessions.get(accountId);
        if (processSession == null || processSession.manualFrame == null) {
            throw new IllegalStateException("人工浏览器尚未准备完成");
        }
        long currentVersion = processSession.manualFrame.version();
        if (drag.frameVersion() > currentVersion || currentVersion - drag.frameVersion() > 5) {
            throw new IllegalStateException("浏览器画面已更新，请重新拖动");
        }
        if (!processSession.manualDrags.offer(drag)) {
            throw new IllegalStateException("上一次拖动正在执行");
        }
    }

    private BrowserType chromiumAfterProcessAttached(
            Playwright playwright, BrowserProcessSession processSession) {
        processSession.attach(playwright);
        return playwright.chromium();
    }

    private BrowserType.LaunchOptions browserLaunchOptions(BrowserType browserType, boolean headless) {
        return new BrowserType.LaunchOptions()
                // 显式使用完整Chromium，避免默认headless shell暴露不同的浏览器特征。
                .setExecutablePath(Path.of(browserType.executablePath()))
                .setHeadless(headless)
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

    static double calculateDistance(double trackWidth, double handleWidth,
                                    boolean scratchCaptcha, double scratchRatio) {
        double availableDistance = Math.max(0, trackWidth - handleWidth);
        if (scratchCaptcha) {
            return availableDistance * Math.max(0.25, Math.min(0.35, scratchRatio));
        }
        return calculateDistance(trackWidth, handleWidth);
    }

    static void validateManualDrag(CaptchaSolveService.ManualDrag drag) {
        if (drag == null || drag.frameVersion() <= 0
                || drag.points() == null || drag.points().size() < 2
                || drag.points().size() > 200) {
            throw new IllegalArgumentException("拖动轨迹无效");
        }
        long previousElapsed = -1;
        for (CaptchaSolveService.DragPoint point : drag.points()) {
            if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                    || point.x() < 0 || point.x() > 1 || point.y() < 0 || point.y() > 1
                    || point.elapsedMs() < previousElapsed || point.elapsedMs() > 10_000) {
                throw new IllegalArgumentException("拖动轨迹无效");
            }
            previousElapsed = point.elapsedMs();
        }
    }

    static boolean isScratchCaptchaText(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.contains("scratch-captcha")
                || normalized.contains("scratch captcha")
                || content.contains("刮刮乐");
    }

    static String sliderMissingMessage(boolean captchaContainerSeen) {
        return captchaContainerSeen
                ? "验证组件已出现，但滑块按钮未加载"
                : "未识别到可拖动滑块";
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
            SliderWaitResult sliderWait = waitForSlider(
                    page, Math.min(deadline, System.currentTimeMillis() + 12_000),
                    progress, attempt);
            SliderTarget target = sliderWait.target();
            if (target == null) {
                if (isLoginPage(page)) {
                    return new RunResult(Outcome.FAILED, null, COOKIE_EXPIRED_MESSAGE);
                }
                boolean confirmedGone = captchaSeen && waitForCaptchaGone(
                        page, Math.min(deadline, System.currentTimeMillis() + 1_500));
                if (!pageShowsLoadFailure(page) && (hasSuccessSignal(page) || confirmedGone)) {
                    return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
                }
                return new RunResult(Outcome.FAILED, null,
                        sliderMissingMessage(sliderWait.captchaContainerSeen()));
            }
            captchaSeen = true;

            if (hasRefreshDialog(page) && attempt < MAX_AUTO_ATTEMPTS) {
                reportProgress(progress, "RESETTING_SESSION",
                        "检测到页面连接中断，正在重新打开消息页", attempt);
                ReopenResult reopenResult = reopenFromHome(context, page, deadline);
                if (reopenResult.page() == null) {
                    return new RunResult(Outcome.FAILED, null, reopenResult.message());
                }
                page = reopenResult.page();
                captchaSeen = false;
                continue;
            }

            reportProgress(progress, "DRAGGING_SLIDER",
                    "第" + attempt + "次：正在拖动滑块", attempt);
            if (!dragSlider(page, target, attempt, sliderWait.scratchCaptcha())) {
                page.waitForTimeout(500);
                continue;
            }
            reportProgress(progress, "WAITING_RESULT",
                    "第" + attempt + "次：正在等待验证结果", attempt);
            boolean captchaGone = waitForCaptchaGone(
                    page, Math.min(deadline, System.currentTimeMillis() + 10_000));
            if (captchaGone && !hasDownloadFailure(page) && !hasRefreshDialog(page)) {
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

    private RunResult waitForManual(Page page, BrowserProcessSession processSession,
                                    long deadline, Consumer<ProgressUpdate> progress) {
        reportProgress(progress, "CAPTURING_MANUAL_FRAME",
                "正在同步服务器验证画面", 0);
        boolean captchaSeen = false;
        long lastFrameAt = 0;
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
            } else if (captchaSeen && waitForCaptchaGone(
                    page, Math.min(deadline, System.currentTimeMillis() + 1_500))) {
                return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
            }
            long now = System.currentTimeMillis();
            if (now - lastFrameAt >= MANUAL_FRAME_INTERVAL_MS) {
                captureManualFrame(page, processSession);
                lastFrameAt = now;
                reportProgress(progress, "WAITING_MANUAL",
                        "服务器画面已同步，请在画面中拖动滑块", 0);
            }
            CaptchaSolveService.ManualDrag drag = processSession.manualDrags.peek();
            if (drag != null) {
                try {
                    reportProgress(progress, "REPLAYING_MANUAL_DRAG",
                            "正在服务器浏览器中执行拖动", 0);
                    replayManualDrag(page, drag);
                    if (waitForCaptchaGone(page,
                            Math.min(deadline, System.currentTimeMillis() + 10_000))) {
                        return new RunResult(Outcome.SOLVED, null, "滑块验证完成");
                    }
                } finally {
                    // 执行完成前保留队列元素，阻止同一浏览器并发接收第二条轨迹。
                    processSession.manualDrags.poll();
                }
                reportProgress(progress, "CAPTURING_MANUAL_FRAME",
                        "平台未放行，正在同步最新验证画面", 0);
                lastFrameAt = 0;
            }
            page.waitForTimeout(100);
        }
        return new RunResult(Outcome.TIMEOUT, null, "人工滑块验证超时");
    }

    private void captureManualFrame(Page page, BrowserProcessSession processSession) {
        byte[] image = page.screenshot(new Page.ScreenshotOptions()
                .setType(ScreenshotType.JPEG)
                .setQuality(60));
        long version = processSession.frameVersion.incrementAndGet();
        processSession.manualFrame = new CaptchaSolveService.ManualFrame(
                version, VIEWPORT_WIDTH, VIEWPORT_HEIGHT, System.currentTimeMillis(),
                Base64.getEncoder().encodeToString(image));
    }

    private void replayManualDrag(Page page, CaptchaSolveService.ManualDrag drag) {
        List<CaptchaSolveService.DragPoint> points = drag.points();
        CaptchaSolveService.DragPoint first = points.getFirst();
        double startX = first.x() * VIEWPORT_WIDTH;
        double startY = first.y() * VIEWPORT_HEIGHT;
        try (CaptchaDragMouse mouse = CaptchaDragMouse.create(page, startX, startY)) {
            mouse.move(startX, startY, 1);
            mouse.down(startX, startY);
            long previousElapsed = first.elapsedMs();
            for (int index = 1; index < points.size(); index++) {
                CaptchaSolveService.DragPoint point = points.get(index);
                long delay = Math.min(120, Math.max(0, point.elapsedMs() - previousElapsed));
                if (delay > 0) {
                    page.waitForTimeout(delay);
                }
                mouse.move(point.x() * VIEWPORT_WIDTH, point.y() * VIEWPORT_HEIGHT, 1);
                previousElapsed = point.elapsedMs();
            }
            CaptchaSolveService.DragPoint last = points.getLast();
            mouse.up(last.x() * VIEWPORT_WIDTH, last.y() * VIEWPORT_HEIGHT);
        }
    }

    private void reportProgress(Consumer<ProgressUpdate> progress, String phase,
                                String message, int attempt) {
        if (progress != null) {
            progress.accept(new ProgressUpdate(phase, message, attempt, MAX_AUTO_ATTEMPTS));
        }
    }

    private SliderWaitResult waitForSlider(Page page, long deadline,
                                           Consumer<ProgressUpdate> progress, int attempt) {
        boolean captchaContainerSeen = false;
        long lastLoadingReportAt = 0;
        while (System.currentTimeMillis() < deadline) {
            if (hasSuccessSignal(page)) {
                return new SliderWaitResult(null, captchaContainerSeen, false);
            }
            SliderTarget target = findSlider(page);
            if (target != null) {
                return new SliderWaitResult(target, true, isScratchCaptcha(page));
            }
            if (isCaptchaContainerVisible(page)) {
                captchaContainerSeen = true;
                long now = System.currentTimeMillis();
                if (now - lastLoadingReportAt >= 1_000) {
                    reportProgress(progress, "WAITING_SLIDER",
                            "第" + attempt + "次：验证组件已出现，正在等待滑块加载", attempt);
                    lastLoadingReportAt = now;
                }
            }
            page.waitForTimeout(250);
        }
        return new SliderWaitResult(null, captchaContainerSeen, false);
    }

    private boolean dragSlider(Page page, SliderTarget target, int attempt,
                               boolean scratchCaptcha) {
        BoundingBox handleBox = target.handle().boundingBox();
        if (handleBox == null) {
            return false;
        }
        simulatePreDragBehavior(page, handleBox);
        return switch (attempt % 3) {
            case 2 -> dragOutOfContainer(page, target, scratchCaptcha);
            case 0 -> dragWithMinimumJerk(page, target, scratchCaptcha);
            default -> dragInContainer(page, target, attempt, scratchCaptcha);
        };
    }

    private boolean dragInContainer(Page page, SliderTarget target, int attempt,
                                    boolean scratchCaptcha) {
        BoundingBox handleBox = target.handle().boundingBox();
        if (handleBox == null) {
            return false;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double startX = handleBox.x + handleBox.width / 2;
        double startY = handleBox.y + handleBox.height / 2;
        BoundingBox trackBox = target.track() == null ? null : target.track().boundingBox();
        double scratchRatio = random.nextDouble(0.25, 0.35);
        double distance = trackBox == null
                ? (scratchCaptcha ? 300 * scratchRatio : 300)
                : calculateDistance(trackBox.width, handleBox.width, scratchCaptcha,
                        scratchRatio);
        int stepsBase;
        int delayMin;
        int delayMax;
        boolean pauseDuringDrag = false;
        switch (attempt) {
            case 1 -> {
                stepsBase = 50;
                delayMin = 30;
                delayMax = 70;
                pauseDuringDrag = true;
            }
            case 2 -> {
                stepsBase = 55;
                delayMin = 40;
                delayMax = 80;
                pauseDuringDrag = true;
            }
            case 3 -> {
                stepsBase = 45;
                delayMin = 25;
                delayMax = 60;
            }
            case 4 -> {
                stepsBase = 60;
                delayMin = 50;
                delayMax = 100;
                pauseDuringDrag = true;
            }
            default -> {
                stepsBase = 40 + random.nextInt(20);
                delayMin = 30 + random.nextInt(30);
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
        Mouse approachMouse = page.mouse();
        // 先自然接近滑块再按下，避免鼠标瞬移到固定中心点。
        approachMouse.move(approachX, approachY, new Mouse.MoveOptions().setSteps(8));
        page.waitForTimeout(random.nextInt(80, 201));
        int approachSteps = random.nextInt(3, 6);
        for (int index = 1; index <= approachSteps; index++) {
            double progress = (double) index / approachSteps;
            double eased = progress * progress * (3 - 2 * progress);
            approachMouse.move(
                    approachX + (actualStartX - approachX) * eased,
                    approachY + (actualStartY - approachY) * eased,
                    new Mouse.MoveOptions().setSteps(5));
            page.waitForTimeout(random.nextInt(12, 38));
        }
        page.waitForTimeout(random.nextInt(100, 251));
        try (CaptchaDragMouse mouse = CaptchaDragMouse.create(page, actualStartX, actualStartY)) {
            mouse.down(actualStartX, actualStartY);
            page.waitForTimeout(random.nextInt(80, 181));
            mouse.move(
                    actualStartX + random.nextDouble(-1.5, 1.5),
                    actualStartY + random.nextDouble(-1.5, 1.5),
                    3);
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
                mouse.move(x, y, 3);
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
                    4);
            page.waitForTimeout(random.nextInt(50, 131));
            mouse.move(
                    actualStartX + distance,
                    actualStartY + random.nextDouble(-3, 3),
                    4);
            int adjustments = random.nextDouble() < 0.7 ? 1 : 2;
            for (int index = 0; index < adjustments; index++) {
                page.waitForTimeout(random.nextInt(40, 101));
                mouse.move(
                        actualStartX + distance + random.nextDouble(-2, 2),
                        actualStartY + random.nextDouble(-2, 2),
                        2);
            }
            page.waitForTimeout(random.nextInt(50, 121));
            mouse.up(actualStartX + distance, lastY);
            return true;
        }
    }

    private void simulatePreDragBehavior(Page page, BoundingBox handleBox) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Mouse mouse = page.mouse();
        int movements = random.nextInt(2, 4);
        for (int index = 0; index < movements; index++) {
            mouse.move(random.nextDouble(100, VIEWPORT_WIDTH - 100),
                    random.nextDouble(100, VIEWPORT_HEIGHT - 100),
                    new Mouse.MoveOptions().setSteps(random.nextInt(3, 8)));
            page.waitForTimeout(random.nextInt(200, 601));
        }
        double startX = handleBox.x + handleBox.width / 2;
        double startY = handleBox.y + handleBox.height / 2;
        mouse.move(startX + random.nextDouble(-30, 30),
                startY + random.nextDouble(-20, 20),
                new Mouse.MoveOptions().setSteps(5));
        page.waitForTimeout(random.nextInt(1_100, 2_501));
    }

    private boolean dragOutOfContainer(Page page, SliderTarget target,
                                       boolean scratchCaptcha) {
        DragGeometry geometry = resolveDragGeometry(target, scratchCaptcha);
        if (geometry == null) {
            return false;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        page.mouse().move(geometry.startX(), geometry.startY(),
                new Mouse.MoveOptions().setSteps(5));
        page.waitForTimeout(random.nextInt(100, 251));
        int steps = 35 + random.nextInt(10);
        double firstTurn = random.nextDouble(0.25, 0.4);
        double secondTurn = random.nextDouble(0.6, 0.78);
        double firstOffset = -random.nextDouble(50, 121);
        double secondOffset = random.nextDouble(50, 121);
        double lastX = geometry.startX();
        try (CaptchaDragMouse mouse = CaptchaDragMouse.create(
                page, geometry.startX(), geometry.startY())) {
            mouse.down(geometry.startX(), geometry.startY());
            page.waitForTimeout(random.nextInt(80, 181));
            for (int index = 1; index <= steps; index++) {
                double progress = (double) index / steps;
                double eased = progress * progress * (3 - 2 * progress);
                double x = geometry.startX() + geometry.distance() * eased;
                if (random.nextDouble() < 0.05 && index > 3 && index < steps - 3) {
                    x = lastX - random.nextDouble(2, 5);
                }
                double firstInfluence = gaussianInfluence(progress, firstTurn);
                double secondInfluence = gaussianInfluence(progress, secondTurn);
                double y = geometry.startY()
                        + Math.sin(Math.PI * progress) * 5
                        + firstOffset * firstInfluence
                        + secondOffset * secondInfluence
                        + random.nextDouble(-5, 5);
                mouse.move(x, y, 1);
                double speedWeight = 1 - Math.sin(Math.PI * progress) * 0.5;
                page.waitForTimeout(random.nextDouble(25, 71) * speedWeight);
                lastX = x;
            }
            double endX = geometry.startX() + geometry.distance();
            double endY = geometry.startY() + random.nextDouble(-15, 15);
            mouse.move(endX + random.nextDouble(5, 15),
                    geometry.startY() + random.nextDouble(-20, 20), 2);
            page.waitForTimeout(random.nextInt(50, 131));
            mouse.move(endX, endY, 2);
            page.waitForTimeout(random.nextInt(50, 121));
            mouse.up(endX, endY);
            return true;
        }
    }

    private boolean dragWithMinimumJerk(Page page, SliderTarget target,
                                        boolean scratchCaptcha) {
        DragGeometry geometry = resolveDragGeometry(target, scratchCaptcha);
        if (geometry == null) {
            return false;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double startX = geometry.startX() + random.nextDouble(-0.5, 0.5);
        double startY = geometry.startY() + random.nextDouble(-0.5, 0.5);
        double distance = geometry.distance() + random.nextDouble(-1, 1);
        int steps = random.nextInt(100, 141);
        double averageDelay = random.nextDouble(700, 1_301) / steps;
        double approachAngle = random.nextDouble(0, Math.PI * 2);
        double approachDistance = random.nextDouble(30, 90);
        page.mouse().move(startX + Math.cos(approachAngle) * approachDistance,
                startY + Math.sin(approachAngle) * approachDistance,
                new Mouse.MoveOptions().setSteps(6));
        page.waitForTimeout(random.nextInt(80, 201));
        page.mouse().move(startX, startY, new Mouse.MoveOptions().setSteps(5));
        page.waitForTimeout(random.nextInt(100, 251));

        int firstPauseStep = random.nextInt(20, 55);
        int secondPauseStep = random.nextBoolean() ? random.nextInt(60, 90) : -1;
        double noise = 0;
        double lastX = startX;
        double driftAmplitude = random.nextDouble(2, 5);
        double driftPhase = random.nextDouble(0, Math.PI * 2);
        try (CaptchaDragMouse mouse = CaptchaDragMouse.create(page, startX, startY)) {
            mouse.down(startX, startY);
            page.waitForTimeout(random.nextInt(80, 181));
            mouse.move(startX + random.nextDouble(-1.5, 1.5),
                    startY + random.nextDouble(-1.5, 1.5), 3);
            page.waitForTimeout(random.nextInt(30, 81));
            for (int index = 1; index <= steps; index++) {
                double progress = (double) index / steps;
                noise = Math.max(-1.2, Math.min(1.2,
                        noise + random.nextDouble(-0.12, 0.12)));
                double x = startX + distance * CaptchaDragMouse.minimumJerk(progress) + noise;
                if (x < lastX) {
                    x = lastX + random.nextDouble(0.1, 0.5);
                }
                double yDrift = Math.sin(progress * Math.PI + driftPhase)
                        * driftAmplitude * 0.3;
                double tremor = random.nextDouble(-1.5, 1.5)
                        * (progress > 0.7 ? 0.5 : 1);
                mouse.move(x, startY + yDrift + tremor, 1);
                page.waitForTimeout(Math.max(3,
                        averageDelay + random.nextDouble(-3.5, 3.5)));
                lastX = x;
                if (index == firstPauseStep || index == secondPauseStep) {
                    page.waitForTimeout(random.nextInt(30, 81));
                }
            }
            double endX = startX + distance;
            mouse.move(endX + random.nextDouble(2, 6),
                    startY + random.nextDouble(-1, 1), 1);
            page.waitForTimeout(random.nextInt(40, 101));
            for (int index = 0; index < random.nextInt(2, 4); index++) {
                mouse.move(endX + random.nextDouble(-0.8, 0.8),
                        startY + random.nextDouble(-1, 1), 1);
                page.waitForTimeout(random.nextInt(40, 121));
            }
            double releaseY = startY - 2 + random.nextDouble(-1, 1);
            mouse.move(endX + random.nextDouble(-0.5, 0.5), releaseY, 1);
            page.waitForTimeout(random.nextInt(150, 351));
            mouse.up(endX, releaseY);
            page.waitForTimeout(random.nextInt(80, 201));
            page.mouse().move(endX + random.nextDouble(20, 60),
                    startY + random.nextDouble(-15, 25),
                    new Mouse.MoveOptions().setSteps(3));
            return true;
        }
    }

    private DragGeometry resolveDragGeometry(SliderTarget target, boolean scratchCaptcha) {
        BoundingBox handleBox = target.handle().boundingBox();
        if (handleBox == null) {
            return null;
        }
        BoundingBox trackBox = target.track() == null ? null : target.track().boundingBox();
        double scratchRatio = ThreadLocalRandom.current().nextDouble(0.25, 0.35);
        double distance = trackBox == null
                ? (scratchCaptcha ? 300 * scratchRatio : 300)
                : calculateDistance(trackBox.width, handleBox.width, scratchCaptcha, scratchRatio);
        return new DragGeometry(handleBox.x + handleBox.width / 2,
                handleBox.y + handleBox.height / 2, distance);
    }

    private double gaussianInfluence(double progress, double center) {
        double difference = progress - center;
        return Math.exp(-(difference * difference) / (2 * 0.05 * 0.05));
    }

    private ReopenResult reopenFromHome(BrowserContext context, Page currentPage, long deadline) {
        try {
            clearRiskCookies(context);
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

    static boolean isPageLoadFailureUrl(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("chrome-error://") || normalized.contains("chromewebdata");
    }

    private boolean pageShowsLoadFailure(Page page) {
        try {
            return isPageLoadFailureUrl(page.url());
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean hasRefreshDialog(Page page) {
        return mainPageTextMatches(page, "(?s).*(连接中断|刷新页面|重新加载页面).*");
    }

    private boolean hasDownloadFailure(Page page) {
        return mainPageTextMatches(page, "(?s).*下载消息失败.*");
    }

    private boolean mainPageTextMatches(Page page, String pattern) {
        try {
            String bodyText = (String) page.evaluate(
                    "() => document.body ? document.body.innerText : ''");
            return bodyText != null && bodyText.matches(pattern);
        } catch (Exception ignored) {
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
        int consecutiveMissingChecks = 0;
        while (System.currentTimeMillis() < deadline) {
            if (pageShowsLoadFailure(page)) {
                return false;
            }
            if (hasSuccessSignal(page)) {
                return true;
            }
            if (hasFailureSignal(page)) {
                return false;
            }
            if (!isCaptchaVisible(page)) {
                if (isLoginPage(page)) {
                    return false;
                }
                consecutiveMissingChecks++;
                if (consecutiveMissingChecks >= 3) {
                    return true;
                }
            } else {
                consecutiveMissingChecks = 0;
            }
            page.waitForTimeout(300);
        }
        return false;
    }

    private boolean isCaptchaVisible(Page page) {
        return findSlider(page) != null || isCaptchaContainerVisible(page);
    }

    private boolean isCaptchaContainerVisible(Page page) {
        for (Frame frame : page.frames()) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                ElementHandle container = findVisibleElement(frame, CAPTCHA_CONTAINER_SELECTORS);
                if (container != null) {
                    return true;
                }
            } catch (Exception ignored) {
                // 验证组件刷新时继续检查其余页面上下文。
            }
        }
        return false;
    }

    private boolean isScratchCaptcha(Page page) {
        for (Frame frame : page.frames()) {
            if (frame.isDetached()) {
                continue;
            }
            try {
                ElementHandle container = findVisibleElement(frame, SCRATCH_CAPTCHA_SELECTORS);
                if (container != null) {
                    return true;
                }
                String bodyText = (String) frame.evaluate(
                        "() => document.body ? document.body.innerText : ''");
                if (isScratchCaptchaText(bodyText)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 验证组件刷新时继续检查其余页面上下文。
            }
        }
        return false;
    }

    boolean hasSuccessSignal(Page page) {
        if (pageShowsLoadFailure(page)) {
            return false;
        }
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
                    || entry.getValue() == null || entry.getValue().isBlank()
                    || isRiskCookie(entry.getKey())) {
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
        RISK_COOKIE_NAMES.forEach(originalCookieMap::remove);
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

    private void clearRiskCookies(BrowserContext context) {
        try {
            List<Cookie> currentCookies = context.cookies();
            List<Cookie> cleanCookies = currentCookies.stream()
                    .filter(cookie -> !isRiskCookie(cookie.name))
                    .toList();
            if (cleanCookies.size() == currentCookies.size()) {
                return;
            }
            context.clearCookies();
            if (!cleanCookies.isEmpty()) {
                context.addCookies(cleanCookies);
            }
        } catch (Exception e) {
            log.debug("清理滑块风险Cookie失败: {}", e.getClass().getSimpleName());
        }
    }

    private static boolean isRiskCookie(String name) {
        return name != null && RISK_COOKIE_NAMES.contains(name.toLowerCase(Locale.ROOT));
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

        private final ArrayBlockingQueue<CaptchaSolveService.ManualDrag> manualDrags =
                new ArrayBlockingQueue<>(1);
        private final AtomicLong frameVersion = new AtomicLong();
        private volatile CaptchaSolveService.ManualFrame manualFrame;
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

    private record DragGeometry(double startX, double startY, double distance) {
    }

    private record SliderWaitResult(SliderTarget target, boolean captchaContainerSeen,
                                    boolean scratchCaptcha) {
    }

}
