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

import java.util.Locale;
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
    /** 可选的推理强度（low/medium/high），留空表示不指定。 */
    private static final String AI_REASONING_EFFORT_SETTING = "ai_reasoning_effort";
    /** 买家自动回复场景的模型（留空继承全局模型）。 */
    private static final String AI_MODEL_REPLY_SETTING = "ai_model_reply";
    /** 文案润色/生成场景的模型（留空继承全局模型）。 */
    private static final String AI_MODEL_POLISH_SETTING = "ai_model_polish";

    private static final String DEFAULT_PROVIDER = "deepseek";
    private static final String DEFAULT_PROTOCOL = "openai";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    @Autowired
    @Lazy
    private SysSettingService sysSettingService;

    @Value("${ai.enabled:false}")
    private boolean aiEnabled;

    /** 每个租户的配置指纹；任一配置变化时重建该租户全部场景客户端。 */
    private final Map<Long, String> cachedFingerprints = new ConcurrentHashMap<>();
    /** key 为 tenantId:scene，不同场景可使用不同强度的模型。 */
    private final Map<String, ChatClient> chatClients = new ConcurrentHashMap<>();

    /** 读写锁，保护ChatClient的线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** AI 调用场景：买家回复与文案生成对模型强度、时延的要求不同。 */
    public enum AIChatScene {
        DEFAULT, REPLY, POLISH
    }

    /**
     * 获取默认场景的ChatClient（兼容旧调用方）。
     */
    public ChatClient getChatClient() {
        return getChatClient(AIChatScene.DEFAULT);
    }

    /**
     * 获取指定场景的ChatClient。
     * 场景模型留空时沿用全局模型；配置变化后自动重建。
     */
    public ChatClient getChatClient(AIChatScene scene) {
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

        AIChatScene targetScene = scene == null ? AIChatScene.DEFAULT : scene;
        String cacheKey = sceneCacheKey(tenantId, targetScene);
        String fingerprint = fingerprint(currentConfig);

        boolean needRebuild = !chatClients.containsKey(cacheKey)
                || !fingerprint.equals(cachedFingerprints.get(tenantId));

        if (needRebuild) {
            lock.writeLock().lock();
            try {
                ChatConfig latestConfig = readCurrentConfig();
                String latestFingerprint = fingerprint(latestConfig);
                boolean stillNeedRebuild = !chatClients.containsKey(cacheKey)
                        || !latestFingerprint.equals(cachedFingerprints.get(tenantId));
                if (stillNeedRebuild) {
                    log.info("[DynamicAI] 检测到AI配置变化，重建ChatClient: provider={}, protocol={}, baseUrl={}, model={}, effort={}, replyModel={}, polishModel={}",
                            latestConfig.provider(), latestConfig.protocol(), latestConfig.baseUrl(), latestConfig.model(),
                            latestConfig.reasoningEffort().isEmpty() ? "未指定" : latestConfig.reasoningEffort(),
                            sceneModel(AIChatScene.REPLY).isEmpty() ? "继承" : sceneModel(AIChatScene.REPLY),
                            sceneModel(AIChatScene.POLISH).isEmpty() ? "继承" : sceneModel(AIChatScene.POLISH));
                    rebuildTenantClients(tenantId, latestConfig);
                    cachedFingerprints.put(tenantId, latestFingerprint);
                    log.info("[DynamicAI] ChatClient重建完成（默认/回复/文案三个场景）");
                }
            } catch (Exception e) {
                log.error("[DynamicAI] ChatClient重建失败", e);
                clearTenantClients(tenantId);
                cachedFingerprints.remove(tenantId);
            } finally {
                lock.writeLock().unlock();
            }
        }

        lock.readLock().lock();
        try {
            return chatClients.get(cacheKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void rebuildTenantClients(Long tenantId, ChatConfig config) {
        clearTenantClients(tenantId);
        for (AIChatScene scene : AIChatScene.values()) {
            chatClients.put(sceneCacheKey(tenantId, scene), buildChatClient(effectiveConfig(config, scene)));
        }
    }

    private void clearTenantClients(Long tenantId) {
        String prefix = tenantId + ":";
        chatClients.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String sceneCacheKey(Long tenantId, AIChatScene scene) {
        return tenantId + ":" + scene.name();
    }

    /**
     * 场景模型：留空表示继承全局模型。
     */
    private String sceneModel(AIChatScene scene) {
        return switch (scene) {
            case REPLY -> text(getSettingValue(AI_MODEL_REPLY_SETTING));
            case POLISH -> text(getSettingValue(AI_MODEL_POLISH_SETTING));
            default -> "";
        };
    }

    private ChatConfig effectiveConfig(ChatConfig config, AIChatScene scene) {
        String overrideModel = sceneModel(scene);
        if (overrideModel.isEmpty()) {
            return config;
        }
        return new ChatConfig(config.provider(), config.protocol(), config.customName(), config.apiKey(),
                config.baseUrl(), resolveSceneModel(config.model(), overrideModel), config.reasoningEffort());
    }

    /**
     * 场景模型留空时继承全局模型。
     */
    static String resolveSceneModel(String globalModel, String sceneOverride) {
        String override = text(sceneOverride);
        return override.isEmpty() ? text(globalModel) : override;
    }

    private String fingerprint(ChatConfig config) {
        return String.join("|",
                config.provider(), config.protocol(), config.baseUrl(), config.apiKey(),
                config.model(), config.reasoningEffort(),
                sceneModel(AIChatScene.REPLY), sceneModel(AIChatScene.POLISH));
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
        info.setReasoningEffort(config.reasoningEffort());

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
                cachedFingerprints.clear();
                chatClients.clear();
            } else {
                cachedFingerprints.remove(tenantId);
                clearTenantClients(tenantId);
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
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(config.model());
        // 推理强度只在用户显式选择时下发，避免对不支持该参数的模型造成 400。
        if (!config.reasoningEffort().isEmpty()) {
            optionsBuilder.reasoningEffort(config.reasoningEffort());
        }
        OpenAiChatOptions chatOptions = optionsBuilder.build();
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
        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .model(config.model());
        int budgetTokens = thinkingBudgetTokens(config.reasoningEffort());
        if (budgetTokens > 0) {
            // Claude 扩展思考要求 max_tokens 大于思考预算，这里保留 2048 的输出余量。
            optionsBuilder.maxTokens(Math.max(2048, budgetTokens + 2048))
                    .thinking(AnthropicApi.ThinkingType.ENABLED, budgetTokens);
        } else {
            optionsBuilder.maxTokens(2048);
        }
        AnthropicChatOptions chatOptions = optionsBuilder.build();
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
                configuredModel.isBlank() ? DEFAULT_MODEL : configuredModel,
                normalizeReasoningEffort(getSettingValue(AI_REASONING_EFFORT_SETTING)));
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
                text(config.apiKey()), text(config.baseUrl()), text(config.model()),
                normalizeReasoningEffort(config.reasoningEffort()));
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

    /**
     * 只接受 low/medium/high，其他值一律视为“不指定”，避免把用户输入直接透传给接口。
     */
    static String normalizeReasoningEffort(String effort) {
        String normalized = text(effort).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            default -> "";
        };
    }

    /**
     * Claude 扩展思考的预算映射，未指定推理强度时返回 0 表示不开启。
     */
    static int thinkingBudgetTokens(String effort) {
        return switch (normalizeReasoningEffort(effort)) {
            case "low" -> 1024;
            case "medium" -> 4096;
            case "high" -> 8192;
            default -> 0;
        };
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
                             String model, String reasoningEffort) {

        /** 兼容未携带推理强度的调用方。 */
        public ChatConfig(String provider, String protocol, String customName, String apiKey, String baseUrl,
                          String model) {
            this(provider, protocol, customName, apiKey, baseUrl, model, "");
        }
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
        private String reasoningEffort;

        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

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
