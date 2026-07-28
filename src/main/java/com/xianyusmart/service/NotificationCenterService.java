package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.xianyusmart.service.notification.NotificationTemplateRenderer;
import com.xianyusmart.service.notification.WebhookSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 通知配置与分发服务
 */
@Slf4j
@Service
public class NotificationCenterService {

    private static final int MAX_CHANNELS_PER_TENANT = 10;
    private static final Set<String> CHANNEL_TYPES = Set.of(
            "WEBHOOK", "WECHAT_WORK", "DINGTALK", "FEISHU", "BARK", "PUSHPLUS", "TELEGRAM"
    );

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
        String channelType = normalizeChannelType(request.getChannelType());
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
        Map<String, String> config = normalizeChannelConfig(channelType, request, channel);
        String webhookUrl = resolveWebhookUrl(channelType, config);
        WebhookSecurity.requireSafeUrl(webhookUrl);
        channel.setChannelName(channelName);
        channel.setChannelType(channelType);
        channel.setWebhookUrl(webhookUrl);
        channel.setConfigJson(writeConfig(config));
        channel.setMessageTemplate(normalizeMessageTemplate(request.getMessageTemplate()));
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
            NotificationRequest request = buildRequest(
                    channel, eventType, accountId, title, content, data);
            PinnedHttpsClient.Response response = httpsClient.post(
                    request.url(), request.headers(), request.body(), Duration.ofSeconds(10));
            sendLog.setHttpStatus(response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("通知渠道返回 HTTP " + response.statusCode());
            }
            requireProviderSuccess(channel.getChannelType(), response.body());
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
        response.setChannelType(normalizeChannelType(channel.getChannelType()));
        response.setWebhookUrl(channel.getWebhookUrl());
        Map<String, String> config = readConfig(channel);
        response.setSecretConfigured(hasSecret(config));
        response.setConfig(maskSecrets(config));
        response.setMessageTemplate(channel.getMessageTemplate());
        response.setEventTypes(new ArrayList<>(splitEvents(channel.getEventTypes())));
        response.setEnabled(Integer.valueOf(1).equals(channel.getEnabled()));
        response.setLastSuccessTime(channel.getLastSuccessTime());
        response.setLastErrorMessage(channel.getLastErrorMessage());
        response.setUpdateTime(channel.getUpdateTime());
        return response;
    }

    private String normalizeChannelType(String value) {
        String normalized = value == null || value.isBlank() ? "WEBHOOK" : value.trim().toUpperCase();
        if (!CHANNEL_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("通知渠道类型无效");
        }
        return normalized;
    }

    private Map<String, String> normalizeChannelConfig(String channelType,
                                                       NotificationChannelReqDTO request,
                                                       XianyuNotificationChannel channel) {
        Map<String, String> config = new LinkedHashMap<>(readConfig(channel));
        if (request.getConfig() != null) {
            for (Map.Entry<String, String> entry : request.getConfig().entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!value.isEmpty()) {
                    if (value.length() > 1000) {
                        throw new IllegalArgumentException("渠道配置内容过长");
                    }
                    config.put(entry.getKey(), value);
                } else if (!isSecretKey(entry.getKey())) {
                    config.remove(entry.getKey());
                }
            }
        }
        if (request.getWebhookUrl() != null && !request.getWebhookUrl().isBlank()) {
            config.put("webhookUrl", request.getWebhookUrl().trim());
        }
        if (request.getSigningSecret() != null && !request.getSigningSecret().isBlank()) {
            config.put("secret", request.getSigningSecret().trim());
        }
        if ("BARK".equals(channelType)) {
            config.putIfAbsent("serverUrl", "https://api.day.app");
        }
        requireConfig(channelType, config);
        return config;
    }

    private void requireConfig(String channelType, Map<String, String> config) {
        switch (channelType) {
            case "WEBHOOK", "WECHAT_WORK", "DINGTALK", "FEISHU" ->
                    requireValue(config, "webhookUrl", "Webhook 地址");
            case "BARK" -> {
                requireValue(config, "serverUrl", "Bark 服务地址");
                requireValue(config, "deviceKey", "Bark Device Key");
            }
            case "PUSHPLUS" -> requireValue(config, "token", "PushPlus Token");
            case "TELEGRAM" -> {
                requireValue(config, "botToken", "Telegram Bot Token");
                requireValue(config, "chatId", "Telegram Chat ID");
            }
            default -> throw new IllegalArgumentException("通知渠道类型无效");
        }
    }

    private void requireValue(Map<String, String> config, String key, String label) {
        if (config.get(key) == null || config.get(key).isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }

    private String resolveWebhookUrl(String channelType, Map<String, String> config) {
        return switch (channelType) {
            case "BARK" -> trimSlash(config.get("serverUrl")) + "/push";
            case "PUSHPLUS" -> "https://www.pushplus.plus/send";
            case "TELEGRAM" -> "https://api.telegram.org/bot" + config.get("botToken") + "/sendMessage";
            default -> config.get("webhookUrl");
        };
    }

    private NotificationRequest buildRequest(XianyuNotificationChannel channel, String eventType,
                                             Long accountId, String title, String content,
                                             Map<String, Object> data) throws Exception {
        String channelType = normalizeChannelType(channel.getChannelType());
        Map<String, String> config = readConfig(channel);
        String message = NotificationTemplateRenderer.render(
                channel.getMessageTemplate(), eventName(eventType), title, content, accountId);
        String url = resolveWebhookUrl(channelType, config);
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "XianYuSmart-Notification/2");

        switch (channelType) {
            case "DINGTALK" -> {
                url = appendDingTalkSignature(url, config.get("secret"));
                payload.put("msgtype", "text");
                payload.put("text", Map.of("content", message));
            }
            case "FEISHU" -> {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                if (config.get("secret") != null && !config.get("secret").isBlank()) {
                    payload.put("timestamp", timestamp);
                    payload.put("sign", feishuSign(timestamp, config.get("secret")));
                }
                payload.put("msg_type", "text");
                payload.put("content", Map.of("text", message));
            }
            case "WECHAT_WORK" -> {
                payload.put("msgtype", "text");
                payload.put("text", Map.of("content", message));
            }
            case "BARK" -> {
                payload.put("device_key", config.get("deviceKey"));
                payload.put("title", title);
                payload.put("body", message);
                if (hasText(config.get("group"))) {
                    payload.put("group", config.get("group"));
                }
            }
            case "PUSHPLUS" -> {
                payload.put("token", config.get("token"));
                payload.put("title", title);
                payload.put("content", message);
                payload.put("template", "txt");
                if (hasText(config.get("topic"))) {
                    payload.put("topic", config.get("topic"));
                }
            }
            case "TELEGRAM" -> {
                payload.put("chat_id", config.get("chatId"));
                payload.put("text", message);
                payload.put("disable_web_page_preview", true);
            }
            default -> {
                payload.put("eventId", UUID.randomUUID().toString());
                payload.put("eventType", eventType);
                payload.put("occurredAt", Instant.now().toString());
                payload.put("accountId", accountId);
                payload.put("title", title);
                payload.put("content", content);
                payload.put("data", data);
            }
        }
        String body = objectMapper.writeValueAsString(payload);
        if ("WEBHOOK".equals(channelType)) {
            String signature = WebhookSecurity.sign(config.get("secret"), body);
            if (!signature.isEmpty()) {
                headers.put("X-XianYuSmart-Signature", signature);
            }
        }
        return new NotificationRequest(url, headers, body);
    }

    private void requireProviderSuccess(String channelType, String responseBody) throws Exception {
        String type = normalizeChannelType(channelType);
        if ("WEBHOOK".equals(type) || responseBody == null || responseBody.isBlank()) {
            return;
        }
        JsonNode root = objectMapper.readTree(responseBody);
        boolean success = switch (type) {
            case "DINGTALK", "WECHAT_WORK" -> root.path("errcode").asInt(-1) == 0;
            case "FEISHU" -> root.path("code").asInt(-1) == 0;
            case "BARK" -> root.path("code").asInt(-1) == 200;
            case "PUSHPLUS" -> root.path("code").asInt(-1) == 200;
            case "TELEGRAM" -> root.path("ok").asBoolean(false);
            default -> true;
        };
        if (!success) {
            throw new IllegalStateException("渠道返回失败: " + limit(responseBody, 200));
        }
    }

    private String appendDingTalkSignature(String url, String secret) throws Exception {
        if (!hasText(secret)) {
            return url;
        }
        long timestamp = System.currentTimeMillis();
        String sign = hmacBase64(timestamp + "\n" + secret, secret);
        return url + (url.contains("?") ? "&" : "?") + "timestamp=" + timestamp
                + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
    }

    private String feishuSign(String timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }

    private String hmacBase64(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, String> readConfig(XianyuNotificationChannel channel) {
        if (channel == null) {
            return Map.of();
        }
        if (channel.getConfigJson() != null && !channel.getConfigJson().isBlank()) {
            try {
                return objectMapper.readValue(channel.getConfigJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("通知渠道配置解析失败: channelId={}", channel.getId());
            }
        }
        Map<String, String> legacy = new LinkedHashMap<>();
        if (hasText(channel.getWebhookUrl())) {
            legacy.put("webhookUrl", channel.getWebhookUrl());
        }
        if (hasText(channel.getSigningSecret())) {
            legacy.put("secret", channel.getSigningSecret());
        }
        return legacy;
    }

    private String writeConfig(Map<String, String> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("通知渠道配置格式无效");
        }
    }

    private Map<String, String> maskSecrets(Map<String, String> config) {
        Map<String, String> result = new LinkedHashMap<>(config);
        result.replaceAll((key, value) -> isSecretKey(key) ? "" : value);
        return result;
    }

    private boolean hasSecret(Map<String, String> config) {
        return config.entrySet().stream().anyMatch(entry ->
                isSecretKey(entry.getKey()) && hasText(entry.getValue()));
    }

    private boolean isSecretKey(String key) {
        return Set.of("secret", "token", "botToken", "deviceKey").contains(key);
    }

    private String normalizeMessageTemplate(String template) {
        String normalized = template == null || template.isBlank()
                ? NotificationTemplateRenderer.DEFAULT_TEMPLATE : template.trim();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("通知模板不能超过1000个字符");
        }
        return normalized;
    }

    private String eventName(String eventType) {
        return switch (eventType) {
            case "ORDER_CREATED" -> "发现订单";
            case "DELIVERY_SUCCESS" -> "发货成功";
            case "DELIVERY_EXCEPTION" -> "发货异常";
            case "ACCOUNT_OFFLINE" -> "账号离线";
            case "CREDENTIAL_EXPIRED" -> "凭证失效";
            case "KAMI_STOCK_LOW" -> "卡密低库存";
            case "TEST" -> "测试通知";
            default -> eventType;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record NotificationRequest(String url, Map<String, String> headers, String body) {
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
