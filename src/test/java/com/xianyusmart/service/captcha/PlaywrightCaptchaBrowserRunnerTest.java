package com.xianyusmart.service.captcha;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
