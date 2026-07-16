package com.xianyusmart.context;

/**
 * 后台任务租户上下文
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        Long tenantId = CURRENT.get();
        return tenantId == null ? UserContext.getUserId() : tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
