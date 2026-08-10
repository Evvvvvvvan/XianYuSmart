package com.xianyusmart.service.captcha;

import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;

/**
 * 滑块拖动鼠标控制器
 */
final class CaptchaDragMouse implements AutoCloseable {

    private final CDPSession cdpSession;
    private final Mouse fallbackMouse;
    private double x;
    private double y;
    private boolean pressed;

    static CaptchaDragMouse create(Page page, double startX, double startY) {
        try {
            return new CaptchaDragMouse(page.context().newCDPSession(page), page.mouse(), startX, startY);
        } catch (Exception ignored) {
            return new CaptchaDragMouse(null, page.mouse(), startX, startY);
        }
    }

    CaptchaDragMouse(CDPSession cdpSession, Mouse fallbackMouse, double startX, double startY) {
        this.cdpSession = cdpSession;
        this.fallbackMouse = fallbackMouse;
        this.x = startX;
        this.y = startY;
    }

    void move(double targetX, double targetY, int steps) {
        int sampleCount = Math.max(1, steps);
        if (cdpSession == null) {
            fallbackMouse.move(targetX, targetY, new Mouse.MoveOptions().setSteps(sampleCount));
            x = targetX;
            y = targetY;
            return;
        }
        double originX = x;
        double originY = y;
        for (int index = 1; index <= sampleCount; index++) {
            double progress = (double) index / sampleCount;
            dispatch("mouseMoved",
                    originX + (targetX - originX) * progress,
                    originY + (targetY - originY) * progress,
                    pressed ? 1 : 0, 0);
        }
    }

    void down(double targetX, double targetY) {
        if (cdpSession == null) {
            fallbackMouse.down();
            x = targetX;
            y = targetY;
        } else {
            dispatch("mousePressed", targetX, targetY, 1, 1);
        }
        pressed = true;
    }

    void up(double targetX, double targetY) {
        if (cdpSession == null) {
            fallbackMouse.up();
            x = targetX;
            y = targetY;
        } else {
            dispatch("mouseReleased", targetX, targetY, 0, 1);
        }
        pressed = false;
    }

    private void dispatch(String type, double targetX, double targetY, int buttons, int clickCount) {
        JsonObject params = new JsonObject();
        params.addProperty("type", type);
        params.addProperty("x", targetX);
        params.addProperty("y", targetY);
        // 显式增量用于生成真实 movementX/movementY，避免平台识别为零增量轨迹。
        params.addProperty("deltaX", roundedDelta(targetX - x));
        params.addProperty("deltaY", roundedDelta(targetY - y));
        params.addProperty("button", "left");
        params.addProperty("buttons", buttons);
        params.addProperty("modifiers", 0);
        params.addProperty("clickCount", clickCount);
        params.addProperty("timestamp", 0);
        cdpSession.send("Input.dispatchMouseEvent", params);
        x = targetX;
        y = targetY;
    }

    private static double roundedDelta(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static double minimumJerk(double progress) {
        double value = Math.max(0, Math.min(1, progress));
        return 10 * Math.pow(value, 3) - 15 * Math.pow(value, 4) + 6 * Math.pow(value, 5);
    }

    @Override
    public void close() {
        try {
            if (pressed) {
                up(x, y);
            }
        } finally {
            if (cdpSession != null) {
                try {
                    cdpSession.detach();
                } catch (Exception ignored) {
                    // 页面关闭时CDP会话可能已自动释放。
                }
            }
        }
    }
}
