package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.NotificationChannelReqDTO;
import com.xianyusmart.controller.dto.NotificationChannelRespDTO;
import com.xianyusmart.entity.XianyuNotificationChannel;
import com.xianyusmart.entity.XianyuNotificationLog;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuNotificationChannelMapper;
import com.xianyusmart.mapper.XianyuNotificationLogMapper;
import com.xianyusmart.service.notification.PinnedHttpsClient;
import com.xianyusmart.service.notification.WebhookSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Webhook 通知配置与分发服务
 */
@Slf4j
@Service
public class NotificationCenterService {

    private static final int MAX_CHANNELS_PER_TENANT = 10;

    public static final Set<String> EVENT_TYPES = Set.of(
            "ORDER_CREATED", "DELIVERY_SUCCESS", "DELIVERY_EXCEPTION",
            "ACCOUNT_OFFLINE", "CREDENTIAL_EXPIRED", "KAMI_STOCK_LOW"
    );

    private final XianyuNotificationChannelMapper channelMapper;
    private final XianyuNotificationLogMapper logMapper;
    private final XianyuAccountMapper accountMapper;
    private final ObjectMapper objectMapper;
    private final PinnedHttpsClient httpsClient;

    public NotificationCenterService(XianyuNotificationChannelMapper channelMapper,
                                     XianyuNotificationLogMapper logMapper,
                                     XianyuAccountMapper accountMapper,
                                     PinnedHttpsClient httpsClient,
                                     ObjectMapper objectMapper) {
        this.channelMapper = channelMapper;
        this.logMapper = logMapper;
        this.accountMapper = accountMapper;
        this.httpsClient = httpsClient;
        this.objectMapper = objectMapper;
    }

    public List<NotificationChannelRespDTO> listChannels() {
        return channelMapper.selectAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public NotificationChannelRespDTO saveChannel(NotificationChannelReqDTO request) {
        String channelName = request.getChannelName().trim();
        if (channelName.length() > 100) {
            throw new IllegalArgumentException("渠道名称不能超过100个字符");
        }
        String webhookUrl = WebhookSecurity.requireSafeUrl(request.getWebhookUrl());
        List<String> eventTypes = normalizeEventTypes(request.getEventTypes());
        XianyuNotificationChannel channel = request.getId() == null
                ? new XianyuNotificationChannel()
                : channelMapper.selectById(request.getId());
        if (channel == null) {
            throw new IllegalArgumentException("通知渠道不存在");
        }
        if (channel.getId() == null) {
            if (channelMapper.selectAll().size() >= MAX_CHANNELS_PER_TENANT) {
                throw new IllegalArgumentException("每个租户最多配置10个通知渠道");
            }
            channel.setTenantId(requireTenantId());
        }
        channel.setChannelName(channelName);
        channel.setWebhookUrl(webhookUrl);
        if (request.getSigningSecret() != null && !request.getSigningSecret().isBlank()) {
            if (request.getSigningSecret().length() > 200) {
                throw new IllegalArgumentException("签名密钥不能超过200个字符");
            }
            channel.setSigningSecret(request.getSigningSecret());
        }
        channel.setEventTypes(String.join(",", eventTypes));
        channel.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        if (channel.getId() == null) {
            channelMapper.insert(channel);
        } else {
            channelMapper.updateById(channel);
        }
        return toResponse(channelMapper.selectById(channel.getId()));
    }

    public void deleteChannel(Long id) {
        if (id == null || channelMapper.deleteById(id) != 1) {
            throw new IllegalArgumentException("通知渠道不存在");
        }
    }

    public Map<String, Object> testChannel(Long id) {
        XianyuNotificationChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new IllegalArgumentException("通知渠道不存在");
        }
        int status = send(channel, "TEST", null, "XianYuSmart 测试通知",
                "通知渠道已连通，可以接收业务事件。", Map.of("source", "manual-test"));
        return Map.of("httpStatus", status, "message", "测试通知发送成功");
    }

    public List<XianyuNotificationLog> listLogs(Integer limit) {
        return logMapper.selectRecent(Math.max(1, Math.min(limit == null ? 50 : limit, 200)));
    }

    @Async("notificationExecutor")
    public void dispatch(String eventType, Long accountId, String title, String content,
                         Map<String, Object> data) {
        if (!EVENT_TYPES.contains(eventType)) {
            log.warn("忽略未知通知事件: {}", eventType);
            return;
        }
        boolean tenantResolved = resolveTenantByAccount(accountId);
        if (TenantContext.get() == null) {
            log.warn("通知事件缺少租户上下文，已停止分发: eventType={}, accountId={}", eventType, accountId);
            return;
        }
        try {
            for (XianyuNotificationChannel channel : channelMapper.selectEnabled()) {
                if (!splitEvents(channel.getEventTypes()).contains(eventType)) {
                    continue;
                }
                try {
                    send(channel, eventType, accountId, title, content, data == null ? Map.of() : data);
                } catch (Exception e) {
                    log.warn("通知发送失败: channelId={}, eventType={}, reason={}",
                            channel.getId(), eventType, e.getMessage());
                }
            }
        } finally {
            if (tenantResolved) {
                TenantContext.clear();
            }
        }
    }

    private boolean resolveTenantByAccount(Long accountId) {
        if (TenantContext.get() != null || accountId == null) {
            return false;
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || account.getTenantId() == null) {
            log.warn("通知事件缺少租户上下文: accountId={}", accountId);
            return false;
        }
        // WebSocket 异步事件按账号恢复租户，防止通知渠道跨租户读取。
        TenantContext.set(account.getTenantId());
        return true;
    }

    private int send(XianyuNotificationChannel channel, String eventType, Long accountId,
                     String title, String content, Map<String, Object> data) {
        XianyuNotificationLog sendLog = new XianyuNotificationLog();
        sendLog.setTenantId(channel.getTenantId() == null ? TenantContext.get() : channel.getTenantId());
        sendLog.setChannelId(channel.getId());
        sendLog.setEventType(eventType);
        sendLog.setXianyuAccountId(accountId);
        sendLog.setTitle(limit(title, 200));
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", UUID.randomUUID().toString());
            payload.put("eventType", eventType);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("accountId", accountId);
            payload.put("title", title);
            payload.put("content", content);
            payload.put("data", data);
            String body = objectMapper.writeValueAsString(payload);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", "XianYuSmart-Webhook/2");
            String signature = WebhookSecurity.sign(channel.getSigningSecret(), body);
            if (!signature.isEmpty()) {
                headers.put("X-XianYuSmart-Signature", signature);
            }
            PinnedHttpsClient.Response response = httpsClient.post(
                    channel.getWebhookUrl(), headers, body, Duration.ofSeconds(10));
            sendLog.setHttpStatus(response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Webhook 返回 HTTP " + response.statusCode());
            }
            sendLog.setSendStatus(1);
            channelMapper.markSuccess(channel.getId());
            logMapper.insert(sendLog);
            return response.statusCode();
        } catch (Exception e) {
            String errorMessage = limit(e.getMessage() == null ? "Webhook 发送失败" : e.getMessage(), 500);
            sendLog.setSendStatus(0);
            sendLog.setErrorMessage(errorMessage);
            channelMapper.markFailure(channel.getId(), errorMessage);
            logMapper.insert(sendLog);
            throw new IllegalStateException(errorMessage);
        }
    }

    private NotificationChannelRespDTO toResponse(XianyuNotificationChannel channel) {
        NotificationChannelRespDTO response = new NotificationChannelRespDTO();
        response.setId(channel.getId());
        response.setChannelName(channel.getChannelName());
        response.setWebhookUrl(channel.getWebhookUrl());
        response.setSecretConfigured(channel.getSigningSecret() != null && !channel.getSigningSecret().isBlank());
        response.setEventTypes(new ArrayList<>(splitEvents(channel.getEventTypes())));
        response.setEnabled(Integer.valueOf(1).equals(channel.getEnabled()));
        response.setLastSuccessTime(channel.getLastSuccessTime());
        response.setLastErrorMessage(channel.getLastErrorMessage());
        response.setUpdateTime(channel.getUpdateTime());
        return response;
    }

    private List<String> normalizeEventTypes(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (!EVENT_TYPES.contains(value)) {
                    throw new IllegalArgumentException("通知事件类型无效");
                }
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个通知事件");
        }
        return List.copyOf(normalized);
    }

    private Set<String> splitEvents(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value != null) {
            for (String eventType : value.split(",")) {
                if (EVENT_TYPES.contains(eventType)) {
                    result.add(eventType);
                }
            }
        }
        return result;
    }

    private Long requireTenantId() {
        Long tenantId = UserContext.getUserId();
        if (tenantId == null) {
            throw new IllegalStateException("登录状态已失效");
        }
        return tenantId;
    }

    private String limit(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), maxLength));
    }
}
