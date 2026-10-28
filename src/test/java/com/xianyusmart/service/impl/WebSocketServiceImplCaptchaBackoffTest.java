package com.xianyusmart.service.impl;

import com.xianyusmart.config.WebSocketConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketServiceImplCaptchaBackoffTest {

    private WebSocketServiceImpl newService(int initial, int max) {
        WebSocketConfig config = new WebSocketConfig();
        config.setCaptchaRetryInitialSeconds(initial);
        config.setCaptchaRetryMaxSeconds(max);
        WebSocketServiceImpl service = new WebSocketServiceImpl();
        ReflectionTestUtils.setField(service, "config", config);
        return service;
    }

    @Test
    void captchaRetryBacksOffExponentiallyAndStopsAtMaximum() {
        WebSocketServiceImpl service = newService(300, 3600);

        assertEquals(300, service.captchaRetryDelaySeconds(1));
        assertEquals(600, service.captchaRetryDelaySeconds(2));
        assertEquals(1200, service.captchaRetryDelaySeconds(3));
        assertEquals(2400, service.captchaRetryDelaySeconds(4));
        assertEquals(3600, service.captchaRetryDelaySeconds(5));
        // 超过上限后不再增长，避免验证未处理时无限拉长等待。
        assertEquals(3600, service.captchaRetryDelaySeconds(9));
    }

    @Test
    void captchaRetryRespectsCustomConfiguration() {
        WebSocketServiceImpl service = newService(60, 600);

        assertEquals(60, service.captchaRetryDelaySeconds(1));
        assertEquals(120, service.captchaRetryDelaySeconds(2));
        assertEquals(480, service.captchaRetryDelaySeconds(4));
        assertEquals(600, service.captchaRetryDelaySeconds(5));
    }
}
