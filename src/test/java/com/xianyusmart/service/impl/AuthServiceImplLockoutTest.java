package com.xianyusmart.service.impl;

import com.xianyusmart.cache.LocalMapCacheServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败锁定回归测试。
 *
 * <p>历史缺陷：recordLoginFailure 把 increment 的结果 put 回缓存，导致计数每次都被重置为 1，
 * 账号永远锁不住。这里固定"第 5 次失败后拒绝登录"的行为。</p>
 */
class AuthServiceImplLockoutTest {

    private AuthServiceImpl service() {
        AuthServiceImpl service = new AuthServiceImpl();
        ReflectionTestUtils.setField(service, "cacheService", new LocalMapCacheServiceImpl());
        return service;
    }

    @Test
    void attemptsBelowThresholdAreAllowed() {
        AuthServiceImpl service = service();
        for (int i = 0; i < 4; i++) {
            service.recordLoginFailure("127.0.0.1");
        }

        assertTrue(service.checkLoginAttempt("127.0.0.1"));
    }

    @Test
    void fifthFailureLocksTheIp() {
        AuthServiceImpl service = service();
        for (int i = 0; i < 5; i++) {
            service.recordLoginFailure("127.0.0.1");
        }

        assertFalse(service.checkLoginAttempt("127.0.0.1"));
    }

    @Test
    void successfulLoginClearsTheCounter() {
        AuthServiceImpl service = service();
        for (int i = 0; i < 5; i++) {
            service.recordLoginFailure("127.0.0.1");
        }
        service.clearLoginFailure("127.0.0.1");

        assertTrue(service.checkLoginAttempt("127.0.0.1"));
    }

    @Test
    void failuresAreCountedPerIp() {
        AuthServiceImpl service = service();
        for (int i = 0; i < 5; i++) {
            service.recordLoginFailure("10.0.0.1");
        }

        assertFalse(service.checkLoginAttempt("10.0.0.1"));
        assertTrue(service.checkLoginAttempt("10.0.0.2"));
    }
}
