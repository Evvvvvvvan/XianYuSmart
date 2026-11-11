package com.xianyusmart.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计数器语义回归测试：登录失败锁定依赖 increment 在普通数值上继续自增。
 */
class LocalMapCacheServiceImplTest {

    @Test
    void incrementStartsAtOne() {
        LocalMapCacheServiceImpl cache = new LocalMapCacheServiceImpl();
        assertEquals(1L, cache.increment("counter"));
        assertEquals(2L, cache.increment("counter"));
    }

    @Test
    void incrementContinuesAfterPutWithPlainNumber() {
        LocalMapCacheServiceImpl cache = new LocalMapCacheServiceImpl();
        // 旧实现遇到 Long 会走"类型不匹配"分支并把计数重置为 1。
        cache.put("counter", 1L, 60, TimeUnit.SECONDS);

        assertEquals(2L, cache.increment("counter"));
        assertEquals(3L, cache.increment("counter"));
    }

    @Test
    void incrementKeepsTtlAfterPlainNumberPut() {
        LocalMapCacheServiceImpl cache = new LocalMapCacheServiceImpl();
        cache.put("counter", 1L, 60, TimeUnit.SECONDS);

        cache.increment("counter");

        long expire = cache.getExpire("counter");
        assertTrue(expire > 0, "计数键应保留原有过期时间，实际剩余=" + expire);
    }
}
