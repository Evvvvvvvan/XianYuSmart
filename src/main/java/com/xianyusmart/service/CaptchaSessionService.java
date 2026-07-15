package com.xianyusmart.service;

import java.util.List;

/**
 * 服务器滑块验证会话服务
 */
public interface CaptchaSessionService {

    /**
     * 创建短时滑块验证会话
     */
    CaptchaSessionResult startSession(Long accountId, String captchaUrl);

    /**
     * 回放页面采集的拖动轨迹
     */
    CaptchaSessionResult replayDrag(Long accountId, String sessionId, List<DragPoint> points);

    /**
     * 关闭滑块验证会话
     */
    void closeSession(Long accountId, String sessionId);

    record DragPoint(double x, double y, int delayMs) {
    }

    record CaptchaSessionResult(
            String sessionId,
            String screenshot,
            boolean success,
            boolean connected,
            String message) {
    }
}
