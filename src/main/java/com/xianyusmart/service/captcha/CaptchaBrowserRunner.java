package com.xianyusmart.service.captcha;

import com.xianyusmart.service.CaptchaSolveService;

import java.util.function.Consumer;

/**
 * 滑块浏览器执行器
 */
public interface CaptchaBrowserRunner {

    enum Outcome {
        SOLVED,
        FAILED,
        TIMEOUT,
        UNSUPPORTED
    }

    record RunResult(Outcome outcome, String cookieText, String message) {
    }

    record ProgressUpdate(String phase, String message, int attempt, int maxAttempts) {
    }

    RunResult run(Long accountId, CaptchaSolveService.Mode mode,
                  String captchaUrl, String cookieText, Consumer<ProgressUpdate> progress);
}
