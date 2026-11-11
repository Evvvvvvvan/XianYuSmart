package com.xianyusmart.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态迁移守卫的契约测试。
 *
 * <p>这些状态机的真实定义写在 SQL 的 WHERE 子句里，因此用反射直接断言守卫存在，
 * 防止后续重构把"租约归属 / 终态保护"悄悄改掉。</p>
 */
class StateMachineGuardContractTest {

    private static String updateSql(Class<?> mapper, String method, Class<?>... params) {
        try {
            Method m = mapper.getMethod(method, params);
            Update update = m.getAnnotation(Update.class);
            assertTrue(update != null, mapper.getSimpleName() + "." + method + " 缺少 @Update");
            return String.join(" ", update.value());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(mapper.getSimpleName() + "." + method + " 签名已变化: " + e.getMessage(), e);
        }
    }

    @Test
    void deliveryCompletionRequiresLeaseOwnership() {
        String sql = updateSql(XianyuGoodsOrderMapper.class, "completeTask", Long.class, String.class);
        assertTrue(sql.contains("lease_owner = #{workerId}"), sql);
    }

    @Test
    void deliveryRetryRequiresLeaseOwnership() {
        String sql = updateSql(XianyuGoodsOrderMapper.class, "retryOrFailTask",
                Long.class, String.class, String.class, LocalDateTime.class, String.class);
        assertTrue(sql.contains("lease_owner = #{workerId}"), sql);
    }

    @Test
    void reviewRequiredCannotDemoteCompletedOrder() {
        String sql = updateSql(XianyuGoodsOrderMapper.class, "markTaskReviewRequired", Long.class, String.class);
        assertTrue(sql.contains("delivery_status <> 'COMPLETED'"), sql);
    }

    @Test
    void riskDeferExcludesTerminalOrders() {
        String sql = updateSql(XianyuGoodsOrderMapper.class, "deferForRisk",
                Long.class, LocalDateTime.class, String.class);
        // 必须覆盖 PENDING：否则风控期间 PENDING 行没有 next_retry_time，会被每秒重扫成热循环。
        assertTrue(sql.contains("delivery_status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')"), sql);
        assertFalse(sql.contains("'COMPLETED'"), sql);
    }

    @Test
    void deliveryMessageCanOnlyBeMarkedSentWhileInFlight() {
        String sql = updateSql(XianyuGoodsOrderMapper.class, "markDeliveryMessageSent", Long.class);
        assertTrue(sql.contains("delivery_message_state = 2"), sql);
    }

    @Test
    void replySuccessRequiresClaimedStateAndOwner() {
        String sql = updateSql(XianyuGoodsAutoReplyRecordMapper.class, "markReplySucceeded",
                Long.class, String.class, String.class);
        assertTrue(sql.contains("state = 2"), sql);
        assertTrue(sql.contains("lease_owner = #{leaseOwner}"), sql);
    }

    @Test
    void replyFailureCannotOverwriteTerminalState() {
        String sql = updateSql(XianyuGoodsAutoReplyRecordMapper.class, "markReplyFailed",
                Long.class, String.class, String.class);
        assertTrue(sql.contains("state IN (0, 2)"), sql);
        assertTrue(sql.contains("lease_owner = #{leaseOwner}"), sql);
    }

    @Test
    void kamiReleaseCoversExternallyProvisionedItems() {
        String sql = updateSql(XianyuKamiItemMapper.class, "releaseReservation", String.class);
        assertFalse(sql.contains("source_type"), "释放预占不能按仓库来源过滤: " + sql);
    }

    @Test
    void retryDoesNotBumpExceptionRevision() {
        String sql = updateSql(XianyuKamiExternalRequestMapper.class, "claimRetry", Long.class);
        assertFalse(sql.contains("exception_revision"), "重试计数不应使人工确认失效: " + sql);
    }

    @Test
    void merchantTaskWritesRequireLeaseOwnership() {
        String claim = updateSql(MerchantTaskMapper.class, "claim", Long.class, String.class, int.class);
        assertTrue(claim.contains("lease_owner = #{workerId}"), claim);

        String complete = updateSql(MerchantTaskMapper.class, "complete", Long.class, String.class, String.class);
        assertTrue(complete.contains("lease_owner = #{workerId}"), complete);

        String fail = updateSql(MerchantTaskMapper.class, "fail",
                Long.class, String.class, String.class, LocalDateTime.class);
        assertTrue(fail.contains("lease_owner = #{workerId}"), fail);
    }
}
