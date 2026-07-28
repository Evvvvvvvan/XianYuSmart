package com.xianyusmart.service;

import com.xianyusmart.context.UserContext;
import com.xianyusmart.service.diagnostics.OperationsHealthEvaluator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营诊断服务
 */
@Service
public class OperationsDiagnosticsService {

    private final JdbcTemplate jdbcTemplate;
    private final WebSocketService webSocketService;
    private final SystemUpdateService systemUpdateService;

    public OperationsDiagnosticsService(JdbcTemplate jdbcTemplate,
                                        WebSocketService webSocketService,
                                        SystemUpdateService systemUpdateService) {
        this.jdbcTemplate = jdbcTemplate;
        this.webSocketService = webSocketService;
        this.systemUpdateService = systemUpdateService;
    }

    public Map<String, Object> overview() {
        Long tenantId = requireTenantId();
        long accountAbnormal = count("""
                SELECT COUNT(*)
                FROM xianyu_account
                WHERE tenant_id = ? AND status <> 1
                """, tenantId);
        long cookieInvalid = count("""
                SELECT COUNT(*)
                FROM xianyu_cookie cookie
                JOIN xianyu_account account ON account.id = cookie.xianyu_account_id
                WHERE account.tenant_id = ? AND cookie.cookie_status <> 1
                """, tenantId);
        long deliveryFailed = count("""
                SELECT COUNT(*)
                FROM xianyu_goods_order
                WHERE tenant_id = ? AND delivery_status = 'FAILED'
                """, tenantId);
        long deliveryReview = count("""
                SELECT COUNT(*)
                FROM xianyu_goods_order
                WHERE tenant_id = ? AND delivery_status = 'REVIEW_REQUIRED'
                """, tenantId);
        long replyFailed = count("""
                SELECT COUNT(*)
                FROM xianyu_goods_auto_reply_record
                WHERE tenant_id = ? AND state = -1
                """, tenantId);
        long lowStock = count("""
                SELECT COUNT(*)
                FROM xianyu_kami_config config
                WHERE config.tenant_id = ?
                  AND config.source_type = 'LOCAL'
                  AND config.alert_enabled = 1
                  AND (
                    (config.alert_threshold_type = 1
                      AND (SELECT COUNT(*) FROM xianyu_kami_item item
                           WHERE item.kami_config_id = config.id AND item.status = 0) < config.alert_threshold_value)
                    OR
                    (config.alert_threshold_type = 2 AND config.total_count > 0
                      AND ((SELECT COUNT(*) FROM xianyu_kami_item item
                            WHERE item.kami_config_id = config.id AND item.status = 0)
                           * 100 / config.total_count) < config.alert_threshold_value)
                  )
                """, tenantId);
        long externalReview = count("""
                SELECT COUNT(*)
                FROM xianyu_kami_external_request
                WHERE tenant_id = ? AND (
                  request_status IN ('FAILED', 'REVIEW_REQUIRED')
                  OR (request_status = 'PROCESSING' AND update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
                )
                """, tenantId);
        long notificationFailed = count("""
                SELECT COUNT(*)
                FROM xianyu_notification_log
                WHERE tenant_id = ? AND send_status = 0
                  AND create_time >= DATE_SUB(NOW(3), INTERVAL 1 DAY)
                """, tenantId);
        List<Long> activeAccountIds = jdbcTemplate.queryForList(
                "SELECT id FROM xianyu_account WHERE tenant_id = ? AND status = 1",
                Long.class, tenantId);
        long websocketDisconnected = activeAccountIds.stream()
                .filter(accountId -> !webSocketService.isConnected(accountId)).count();
        long versionWarning = 0;
        String versionAction = "当前已是最新版本";
        try {
            var version = systemUpdateService.checkUpdate();
            if (Boolean.TRUE.equals(version.getHasUpdate())) {
                versionWarning = 1;
                versionAction = "发现新版本 " + version.getLatestVersion() + "，可在右上角自动更新";
            }
        } catch (Exception e) {
            versionWarning = 1;
            versionAction = "版本服务暂时不可用，请稍后重试";
        }
        boolean updateAgentAvailable = Boolean.TRUE.equals(systemUpdateService.updateAgentStatus().get("available"));
        long updateAgentWarning = updateAgentAvailable ? 0 : 1;

        List<Map<String, Object>> checks = List.of(
                check("DATABASE", "数据库服务", 0, "检查数据库连接"),
                check("WEBSOCKET", "实时连接", websocketDisconnected, "系统正在自动恢复断开的账号连接"),
                check("ACCOUNT", "账号连接", accountAbnormal + cookieInvalid, "检查异常账号或更新登录凭证"),
                check("DELIVERY", "自动发货", deliveryFailed + deliveryReview, "处理失败订单和待人工核对订单"),
                check("REPLY", "自动回复", replyFailed, "检查失败回复记录"),
                check("STOCK", "卡密库存", lowStock, "补充低库存卡密仓库"),
                check("EXTERNAL_SUPPLY", "外部卡密供货", externalReview, "核对失败或不确定的外部供货请求"),
                check("NOTIFICATION", "通知渠道", notificationFailed, "检查近24小时发送失败的通知"),
                check("VERSION", "版本状态", versionWarning, versionAction),
                check("UPDATE_AGENT", "自动更新服务", updateAgentWarning,
                        updateAgentAvailable ? "自动更新服务运行正常" : "自动更新服务尚未就绪")
        );
        long criticalCount = deliveryFailed + deliveryReview + externalReview;
        long warningCount = accountAbnormal + cookieInvalid + websocketDisconnected
                + replyFailed + lowStock + notificationFailed + versionWarning + updateAgentWarning;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overallStatus", OperationsHealthEvaluator.overallStatus(criticalCount, warningCount));
        result.put("criticalCount", criticalCount);
        result.put("warningCount", warningCount);
        result.put("checks", checks);
        return result;
    }

    public List<Map<String, Object>> exceptions(Integer requestedLimit) {
        Long tenantId = requireTenantId();
        int limit = Math.max(1, Math.min(requestedLimit == null ? 100 : requestedLimit, 200));
        // 统一异常列表按发生时间排序，运营人员无需跨页面定位失败原因。
        return jdbcTemplate.queryForList("""
                SELECT exception_type AS exceptionType,
                       account_id AS accountId,
                       target_id AS targetId,
                       title,
                       reason,
                       status,
                       occurred_at AS occurredAt
                FROM (
                    SELECT 'DELIVERY' AS exception_type,
                           xianyu_account_id AS account_id,
                           order_id AS target_id,
                           COALESCE(goods_title, '自动发货订单') AS title,
                           COALESCE(last_error_message, fail_reason, '等待人工核对') AS reason,
                           delivery_status AS status,
                           create_time AS occurred_at
                    FROM xianyu_goods_order
                    WHERE tenant_id = ? AND delivery_status IN ('FAILED', 'REVIEW_REQUIRED')
                    UNION ALL
                    SELECT 'AUTO_REPLY',
                           xianyu_account_id,
                           pnm_id,
                           '自动回复失败',
                           COALESCE(last_error_message, '回复发送失败'),
                           'FAILED',
                           create_time
                    FROM xianyu_goods_auto_reply_record
                    WHERE tenant_id = ? AND state = -1
                    UNION ALL
                    SELECT 'EXTERNAL_SUPPLY',
                           xianyu_account_id,
                           order_id,
                           '外部卡密供货待核对',
                           COALESCE(error_message, '请求状态不确定'),
                           request_status,
                           create_time
                    FROM xianyu_kami_external_request
                    WHERE tenant_id = ? AND (
                      request_status IN ('FAILED', 'REVIEW_REQUIRED')
                      OR (request_status = 'PROCESSING' AND update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
                    )
                ) exceptions
                ORDER BY occurred_at DESC
                LIMIT ?
                """, tenantId, tenantId, tenantId, limit);
    }

    private Map<String, Object> check(String key, String name, long count, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("name", name);
        result.put("count", count);
        result.put("status", count > 0 ? "WARNING" : "HEALTHY");
        result.put("action", action);
        return result;
    }

    private long count(String sql, Long tenantId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    private Long requireTenantId() {
        Long tenantId = UserContext.getUserId();
        if (tenantId == null) {
            throw new IllegalStateException("登录状态已失效");
        }
        return tenantId;
    }
}
