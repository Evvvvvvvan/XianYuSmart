package com.xianyusmart.config.rag;

import com.xianyusmart.service.SysSettingService;
import com.xianyusmart.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_MODEL = "deepseek-v3";

    @Autowired
    @Lazy
    private SysSettingService sysSettingService;

    @Value("${ai.enabled:false}")
    private boolean aiEnabled;

    /** 每个租户独立缓存AI客户端和模型配置。 */
    private final Map<Long, String> cachedApiKeys = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedBaseUrls = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedModels = new ConcurrentHashMap<>();
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
        // 从数据库读取当前配置
        String currentApiKey = getSettingValue(AI_API_KEY_SETTING);
        String currentBaseUrl = getSettingValue(AI_BASE_URL_SETTING);
        String currentModel = getSettingValue(AI_MODEL_SETTING);

        if (currentApiKey == null || currentApiKey.trim().isEmpty()) {
            log.debug("[DynamicAI] API Key未配置，AI功能不可用");
            return null;
        }

        // 检查配置是否变化，需要重建
        boolean needRebuild = !chatClients.containsKey(tenantId)
                || !currentApiKey.equals(cachedApiKeys.get(tenantId))
                || !safeEquals(currentBaseUrl, cachedBaseUrls.get(tenantId))
                || !safeEquals(currentModel, cachedModels.get(tenantId));

        if (needRebuild) {
            lock.writeLock().lock();
            try {
                // 双重检查，防止并发重建
                boolean stillNeedRebuild = !chatClients.containsKey(tenantId)
                        || !currentApiKey.equals(cachedApiKeys.get(tenantId))
                        || !safeEquals(currentBaseUrl, cachedBaseUrls.get(tenantId))
                        || !safeEquals(currentModel, cachedModels.get(tenantId));

                if (stillNeedRebuild) {
                    log.info("[DynamicAI] 检测到AI配置变化，重建ChatClient: baseUrl={}, model={}, apiKey={}***{}",
                            currentBaseUrl, currentModel,
                            currentApiKey.substring(0, Math.min(4, currentApiKey.length())),
                            currentApiKey.length() > 8 ? currentApiKey.substring(currentApiKey.length() - 4) : "****");

                    chatClients.put(tenantId, buildChatClient(currentApiKey, currentBaseUrl, currentModel));
                    cachedApiKeys.put(tenantId, currentApiKey);
                    putOrRemove(cachedBaseUrls, tenantId, currentBaseUrl);
                    putOrRemove(cachedModels, tenantId, currentModel);

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
        String apiKey = getSettingValue(AI_API_KEY_SETTING);
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 获取AI状态信息
     */
    public AIStatusInfo getStatusInfo() {
        AIStatusInfo info = new AIStatusInfo();
        info.setEnabled(aiEnabled);

        if (!aiEnabled) {
            info.setAvailable(false);
            info.setMessage("AI功能未启用(ai.enabled=false)");
            return info;
        }

        String apiKey = getSettingValue(AI_API_KEY_SETTING);
        String baseUrl = getSettingValue(AI_BASE_URL_SETTING);
        String model = getSettingValue(AI_MODEL_SETTING);

        info.setBaseUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        info.setModel(model != null ? model : DEFAULT_MODEL);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            info.setAvailable(false);
            info.setMessage("API Key未配置，请在系统设置中配置AI API Key");
        } else {
            info.setAvailable(true);
            info.setApiKeyConfigured(true);
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
                chatClients.clear();
            } else {
                cachedApiKeys.remove(tenantId);
                cachedBaseUrls.remove(tenantId);
                cachedModels.remove(tenantId);
                chatClients.remove(tenantId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 构建ChatClient实例
     */
    private ChatClient buildChatClient(String apiKey, String baseUrl, String model) {
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl.trim() : DEFAULT_BASE_URL;
        String effectiveModel = (model != null && !model.trim().isEmpty()) ? model.trim() : DEFAULT_MODEL;

        // 创建OpenAiApi实例
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(new SimpleApiKey(apiKey.trim()))
                .baseUrl(effectiveBaseUrl)
                .build();

        // 创建ChatModel
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(effectiveModel)
                .temperature(0.7)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();

        // 创建ChatClient
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个闲鱼智能客服助手")
                .build();
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

    private void putOrRemove(Map<Long, String> values, Long tenantId, String value) {
        if (value == null) {
            values.remove(tenantId);
        } else {
            values.put(tenantId, value);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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
    }
}
