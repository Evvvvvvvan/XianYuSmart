package com.xianyusmart.config.rag;

import com.xianyusmart.service.SysSettingService;
import com.xianyusmart.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 动态AI ChatClient管理器
 * 从数据库读取API Key，动态创建/重建ChatClient，无需重启服务
 * 线程安全：使用ReadWriteLock保护ChatClient的读写
 *
 * @date 2026/4/23
 */
@Slf4j
@Component
public class DynamicAIChatClientManager {

    private static final String AI_API_KEY_SETTING = "ai_api_key";
    private static final String AI_BASE_URL_SETTING = "ai_base_url";
    private static final String AI_MODEL_SETTING = "ai_model";
    private static final String AI_PROVIDER_SETTING = "ai_provider";
    private static final String AI_PROTOCOL_SETTING = "ai_protocol";
    private static final String AI_CUSTOM_NAME_SETTING = "ai_custom_name";

    private static final String DEFAULT_PROVIDER = "deepseek";
    private static final String DEFAULT_PROTOCOL = "openai";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    @Autowired
    @Lazy
    private SysSettingService sysSettingService;

    @Value("${ai.enabled:false}")
    private boolean aiEnabled;

    /** 每个租户独立缓存AI客户端和模型配置。 */
    private final Map<Long, String> cachedApiKeys = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedBaseUrls = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedModels = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedProviders = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedProtocols = new ConcurrentHashMap<>();
    private final Map<Long, ChatClient> chatClients = new ConcurrentHashMap<>();

    /** 读写锁，保护ChatClient的线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 获取ChatClient实例
     * 如果API Key未配置或为空，返回null
     * 如果API Key发生变化，自动重建ChatClient
     *
     * @return ChatClient实例，未配置API Key时返回null
     */
    public ChatClient getChatClient() {
        if (!aiEnabled) {
            log.debug("[DynamicAI] AI功能未启用(ai.enabled=false)");
            return null;
        }

        Long tenantId = currentTenantId();
        ChatConfig currentConfig = readCurrentConfig();

        if (currentConfig.apiKey().isBlank()) {
            log.debug("[DynamicAI] API Key未配置，AI功能不可用");
            return null;
        }

        // 检查配置是否变化，需要重建
        boolean needRebuild = !chatClients.containsKey(tenantId)
                || !currentConfig.apiKey().equals(cachedApiKeys.get(tenantId))
                || !currentConfig.baseUrl().equals(cachedBaseUrls.get(tenantId))
                || !currentConfig.model().equals(cachedModels.get(tenantId))
                || !currentConfig.provider().equals(cachedProviders.get(tenantId))
                || !currentConfig.protocol().equals(cachedProtocols.get(tenantId));

        if (needRebuild) {
            lock.writeLock().lock();
            try {
                // 双重检查，防止并发重建
                boolean stillNeedRebuild = !chatClients.containsKey(tenantId)
                        || !currentConfig.apiKey().equals(cachedApiKeys.get(tenantId))
                        || !currentConfig.baseUrl().equals(cachedBaseUrls.get(tenantId))
                        || !currentConfig.model().equals(cachedModels.get(tenantId))
                        || !currentConfig.provider().equals(cachedProviders.get(tenantId))
                        || !currentConfig.protocol().equals(cachedProtocols.get(tenantId));

                if (stillNeedRebuild) {
                    log.info("[DynamicAI] 检测到AI配置变化，重建ChatClient: provider={}, protocol={}, baseUrl={}, model={}",
                            currentConfig.provider(), currentConfig.protocol(), currentConfig.baseUrl(), currentConfig.model());

                    chatClients.put(tenantId, buildChatClient(currentConfig));
                    cachedApiKeys.put(tenantId, currentConfig.apiKey());
                    cachedBaseUrls.put(tenantId, currentConfig.baseUrl());
                    cachedModels.put(tenantId, currentConfig.model());
                    cachedProviders.put(tenantId, currentConfig.provider());
                    cachedProtocols.put(tenantId, currentConfig.protocol());

                    log.info("[DynamicAI] ChatClient重建完成");
                }
            } catch (Exception e) {
                log.error("[DynamicAI] ChatClient重建失败", e);
                chatClients.remove(tenantId);
                cachedApiKeys.remove(tenantId);
            } finally {
                lock.writeLock().unlock();
            }
        }

        lock.readLock().lock();
        try {
            return chatClients.get(tenantId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 检查AI是否可用（API Key已配置且ai.enabled=true）
     */
    public boolean isAvailable() {
        if (!aiEnabled) {
            return false;
        }
        ChatConfig config = readCurrentConfig();
        if (config.apiKey().isBlank()) {
            return false;
        }
        try {
            resolveChatEndpoint(config);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 获取AI状态信息
     */
    public AIStatusInfo getStatusInfo() {
        AIStatusInfo info = new AIStatusInfo();
        info.setEnabled(aiEnabled);
        ChatConfig config = readCurrentConfig();
        info.setProvider(config.provider());
        info.setProtocol(config.protocol());
        info.setBaseUrl(config.baseUrl());
        info.setModel(config.model());

        String endpointError = "";
        try {
            info.setEndpoint(resolveChatEndpoint(config).endpoint());
        } catch (IllegalArgumentException e) {
            info.setEndpoint("");
            endpointError = e.getMessage();
        }

        if (!aiEnabled) {
            info.setAvailable(false);
            info.setMessage("AI功能未启用(ai.enabled=false)");
            return info;
        }

        info.setApiKeyConfigured(!config.apiKey().isBlank());
        if (config.apiKey().isBlank()) {
            info.setAvailable(false);
            info.setMessage("API Key未配置，请在系统设置中配置AI API Key");
        } else if (!endpointError.isBlank()) {
            info.setAvailable(false);
            info.setMessage(endpointError);
        } else {
            info.setAvailable(true);
            info.setMessage("AI服务可用");
        }

        return info;
    }

    /**
     * 强制重建ChatClient（配置变更时调用）
     */
    public void forceRebuild() {
        log.info("[DynamicAI] 收到强制重建信号，清除缓存");
        lock.writeLock().lock();
        try {
            Long tenantId = TenantContext.get();
            if (tenantId == null) {
                cachedApiKeys.clear();
                cachedBaseUrls.clear();
                cachedModels.clear();
                cachedProviders.clear();
                cachedProtocols.clear();
                chatClients.clear();
            } else {
                cachedApiKeys.remove(tenantId);
                cachedBaseUrls.remove(tenantId);
                cachedModels.remove(tenantId);
                cachedProviders.remove(tenantId);
                cachedProtocols.remove(tenantId);
                chatClients.remove(tenantId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 构建ChatClient实例
     */
    private ChatClient buildChatClient(ChatConfig config) {
        AIEndpointResolver.Endpoint endpoint = resolveChatEndpoint(config);
        ChatModel chatModel = "anthropic".equals(config.protocol())
                ? buildAnthropicChatModel(config, endpoint)
                : buildOpenAiChatModel(config, endpoint);

        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个闲鱼智能客服助手")
                .build();
    }

    private ChatModel buildOpenAiChatModel(ChatConfig config, AIEndpointResolver.Endpoint endpoint) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(new SimpleApiKey(config.apiKey()))
                .baseUrl(endpoint.baseUrl())
                .completionsPath(endpoint.path())
                .build();
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(config.model())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();
    }

    private ChatModel buildAnthropicChatModel(ChatConfig config, AIEndpointResolver.Endpoint endpoint) {
        AnthropicApi anthropicApi = AnthropicApi.builder()
                .apiKey(new SimpleApiKey(config.apiKey()))
                .baseUrl(endpoint.baseUrl())
                .completionsPath(endpoint.path())
                .build();
        AnthropicChatOptions chatOptions = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(2048)
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(chatOptions)
                .build();
    }

    /**
     * 使用未保存的表单配置发送真实请求，返回可视化连接结果。
     */
    public ConnectionTestResult testConnection(ChatConfig config, String message) {
        long startNanos = System.nanoTime();
        ChatConfig effectiveConfig;
        AIEndpointResolver.Endpoint endpoint;
        try {
            effectiveConfig = validateTestConfig(config);
            endpoint = resolveChatEndpoint(effectiveConfig);
            String reply = buildChatClient(effectiveConfig).prompt(message.trim()).call().content();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (reply == null || reply.trim().isEmpty()) {
                return new ConnectionTestResult(false, "", latencyMs, displayProvider(effectiveConfig),
                        effectiveConfig.protocol(), effectiveConfig.model(), endpoint.endpoint(), "接口已连接，但模型返回内容为空");
            }
            return new ConnectionTestResult(true, reply.trim(), latencyMs, displayProvider(effectiveConfig),
                    effectiveConfig.protocol(), effectiveConfig.model(), endpoint.endpoint(), "连接成功");
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            ChatConfig safeConfig = config == null ? new ChatConfig("custom", DEFAULT_PROTOCOL, "", "", "", "") : config;
            String endpointValue = "";
            try {
                endpointValue = resolveChatEndpoint(safeConfig).endpoint();
            } catch (Exception ignored) {
                // 地址无效时保留空值，避免测试接口再次抛出异常。
            }
            return new ConnectionTestResult(false, "", latencyMs, displayProvider(safeConfig),
                    normalizeProtocol(safeConfig.protocol(), safeConfig.provider()), text(safeConfig.model()), endpointValue,
                    sanitizeError(e, safeConfig.apiKey()));
        }
    }

    private ChatConfig readCurrentConfig() {
        String configuredBaseUrl = text(getSettingValue(AI_BASE_URL_SETTING));
        String configuredModel = text(getSettingValue(AI_MODEL_SETTING));
        String configuredProvider = text(getSettingValue(AI_PROVIDER_SETTING));
        String provider = configuredProvider.isBlank()
                ? (configuredBaseUrl.isBlank() && configuredModel.isBlank() ? DEFAULT_PROVIDER : "custom")
                : configuredProvider;
        return new ChatConfig(provider, normalizeProtocol(getSettingValue(AI_PROTOCOL_SETTING), provider),
                text(getSettingValue(AI_CUSTOM_NAME_SETTING)), text(getSettingValue(AI_API_KEY_SETTING)),
                configuredBaseUrl.isBlank() ? DEFAULT_BASE_URL : configuredBaseUrl,
                configuredModel.isBlank() ? DEFAULT_MODEL : configuredModel);
    }

    private ChatConfig validateTestConfig(ChatConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("连接配置不能为空");
        }
        if (text(config.apiKey()).isBlank()) {
            throw new IllegalArgumentException("API Key不能为空");
        }
        if (text(config.baseUrl()).isBlank()) {
            throw new IllegalArgumentException("API Base URL不能为空");
        }
        if (text(config.model()).isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        return new ChatConfig(text(config.provider()), normalizeProtocol(config.protocol(), config.provider()), text(config.customName()),
                text(config.apiKey()), text(config.baseUrl()), text(config.model()));
    }

    private AIEndpointResolver.Endpoint resolveChatEndpoint(ChatConfig config) {
        AIEndpointResolver.Capability capability = "anthropic".equals(normalizeProtocol(config.protocol(), config.provider()))
                ? AIEndpointResolver.Capability.ANTHROPIC_CHAT
                : AIEndpointResolver.Capability.OPENAI_CHAT;
        return AIEndpointResolver.resolve(config.baseUrl(), capability);
    }

    private String displayProvider(ChatConfig config) {
        if ("custom".equalsIgnoreCase(text(config.provider())) && !text(config.customName()).isBlank()) {
            return text(config.customName());
        }
        return text(config.provider()).isBlank() ? "第三方中转站" : text(config.provider());
    }

    private String sanitizeError(Exception exception, String apiKey) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = text(cause.getMessage());
        if (message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        if (!text(apiKey).isBlank()) {
            message = message.replace(text(apiKey), "***");
        }
        return message.length() > 600 ? message.substring(0, 600) : message;
    }

    private static String normalizeProtocol(String protocol, String provider) {
        if ("anthropic".equalsIgnoreCase(text(protocol))) {
            return "anthropic";
        }
        if ("openai".equalsIgnoreCase(text(protocol))) {
            return "openai";
        }
        return "anthropic".equalsIgnoreCase(text(provider)) ? "anthropic" : DEFAULT_PROTOCOL;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String getSettingValue(String key) {
        try {
            return sysSettingService.getSettingValue(key);
        } catch (Exception e) {
            log.warn("[DynamicAI] 读取配置失败: key={}", key, e);
            return null;
        }
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? 0L : tenantId;
    }

    public record ChatConfig(String provider, String protocol, String customName, String apiKey, String baseUrl,
                             String model) {
    }

    public record ConnectionTestResult(boolean success, String reply, long latencyMs, String provider,
                                       String protocol, String model, String endpoint, String message) {
    }

    /**
     * AI状态信息
     */
    public static class AIStatusInfo {
        private boolean enabled;
        private boolean available;
        private boolean apiKeyConfigured;
        private String message;
        private String baseUrl;
        private String model;
        private String provider;
        private String protocol;
        private String endpoint;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public boolean isApiKeyConfigured() { return apiKeyConfigured; }
        public void setApiKeyConfigured(boolean apiKeyConfigured) { this.apiKeyConfigured = apiKeyConfigured; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }
}
