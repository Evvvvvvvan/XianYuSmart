package com.xianyusmart.service.captcha;

import com.xianyusmart.service.CaptchaSolveService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaywrightCaptchaBrowserRunnerTest {

    @Test
    void browserFailureMessageIncludesSanitizedRootCause() {
        Exception failure = new IllegalStateException(
                "Failed to launch driver",
                new IOException("Cannot run program /tmp/playwright-java/node: No such file or directory token=secret"));

        String message = PlaywrightCaptchaBrowserRunner.browserFailureMessage("创建浏览器进程", failure);

        assertTrue(message.contains("Failed to launch driver"));
        assertTrue(message.contains("No such file or directory"));
        assertTrue(message.contains("token=<redacted>"));
        assertFalse(message.contains("secret"));
    }

    @Test
    void loginPageUrlCoversPassportMiniLoginAndLoginHost() {
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginPageUrl(
                "https://passport.goofish.com/mini_login.htm?lang=zh_cn&appName=xianyu"));
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginPageUrl(
                "https://login.taobao.com/member/login.jhtml?redirectURL=x"));
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginPageUrl("https://www.goofish.com/login?redirect=/im"));
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginPageUrl(
                "https://passport.taobao.com/ac/havana/login.htm"));
        assertFalse(PlaywrightCaptchaBrowserRunner.isLoginPageUrl("https://www.goofish.com/im?spm=a21ybx"));
        assertFalse(PlaywrightCaptchaBrowserRunner.isLoginPageUrl("about:blank"));
        assertFalse(PlaywrightCaptchaBrowserRunner.isLoginPageUrl(null));
    }

    @Test
    void loginBodyCoversPhoneScanCopyFromCaptchaRedirect() {
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginBody("请使用手机扫码登录"));
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginBody("扫码登录 / 密码登录"));
        assertTrue(PlaywrightCaptchaBrowserRunner.isLoginBody("手机号登录"));
        assertFalse(PlaywrightCaptchaBrowserRunner.isLoginBody("闲鱼消息 全部 未读 已读"));
        assertFalse(PlaywrightCaptchaBrowserRunner.isLoginBody(""));
        assertFalse(PlaywrightCaptchaBrowserRunner.isLoginBody(null));
    }

    @Test
    void rightDragCaptchaUsesWholeTrackDistance() {
        // 拖动到最右边的验证要求滑块抵达轨道末端，距离必须等于轨道宽度减去句柄宽度。
        assertEquals(280.0, PlaywrightCaptchaBrowserRunner.calculateDistance(320.0, 40.0));
        assertEquals(260.0, PlaywrightCaptchaBrowserRunner.calculateDistance(300.0, 40.0, false, 0.3));
        assertEquals(600.0, PlaywrightCaptchaBrowserRunner.calculateDistance(1200.0, 40.0));
        assertEquals(0.0, PlaywrightCaptchaBrowserRunner.calculateDistance(40.0, 40.0));
    }

    @Test
    void scratchCaptchaDistanceUsesRatioOfAvailableTrack() {
        assertEquals(78.0, PlaywrightCaptchaBrowserRunner.calculateDistance(300.0, 40.0, true, 0.3));
        // 比率被限制在0.25-0.35之间，避免越界拖出可视区域。
        assertEquals(91.0, PlaywrightCaptchaBrowserRunner.calculateDistance(300.0, 40.0, true, 0.9));
    }

    @Test
    void sliderMissingMessagePointsToManualFallback() {
        assertTrue(PlaywrightCaptchaBrowserRunner.sliderMissingMessage(true).contains("人工拖动"));
        assertTrue(PlaywrightCaptchaBrowserRunner.sliderMissingMessage(false).contains("人工拖动"));
    }

    @Test
    void manualDragValidationRejectsOutOfRangePoints() {
        CaptchaSolveService.ManualDrag valid = new CaptchaSolveService.ManualDrag(3L, List.of(
                new CaptchaSolveService.DragPoint(0.1, 0.5, 0),
                new CaptchaSolveService.DragPoint(0.9, 0.5, 420)));
        PlaywrightCaptchaBrowserRunner.validateManualDrag(valid);

        CaptchaSolveService.ManualDrag outOfRange = new CaptchaSolveService.ManualDrag(3L, List.of(
                new CaptchaSolveService.DragPoint(0.1, 0.5, 0),
                new CaptchaSolveService.DragPoint(1.4, 0.5, 420)));
        assertThrows(IllegalArgumentException.class,
                () -> PlaywrightCaptchaBrowserRunner.validateManualDrag(outOfRange));

        CaptchaSolveService.ManualDrag unsorted = new CaptchaSolveService.ManualDrag(3L, List.of(
                new CaptchaSolveService.DragPoint(0.1, 0.5, 500),
                new CaptchaSolveService.DragPoint(0.4, 0.5, 100)));
        assertThrows(IllegalArgumentException.class,
                () -> PlaywrightCaptchaBrowserRunner.validateManualDrag(unsorted));
    }
}
