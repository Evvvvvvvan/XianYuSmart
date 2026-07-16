package com.xianyusmart.config.rag;

import com.xianyusmart.service.SysSettingService;
import com.xianyusmart.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 动态 VectorStore 管理器
 * 支持延迟初始化和热更新
 * 首次使用时才创建 VectorStore，配置变更后可重建
 *
 * @date 2026/4/24
 */
@Slf4j
@Component
public class DynamicVectorStoreManager {

    // AI 对话配置（默认共用）
    private static final String AI_API_KEY_SETTING = "ai_api_key";
    private static final String AI_BASE_URL_SETTING = "ai_base_url";

    // Embedding 模型独立配置（可选，默认使用AI对话配置）
    private static final String EMBEDDING_API_KEY_SETTING = "ai_embedding_api_key";
    private static final String EMBEDDING_BASE_URL_SETTING = "ai_embedding_base_url";
    private static final String EMBEDDING_MODEL_SETTING = "ai_embedding_model";

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";

    @Value("${ai.vectorstore.simple.file-path:dbdata/vectorstore.json}")
    private String vectorStoreFilePath;

    @Autowired
    private SysSettingService sysSettingService;

    /** 每个租户独立缓存向量库和模型配置。 */
    private final Map<Long, VectorStore> vectorStores = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedApiKeys = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedBaseUrls = new ConcurrentHashMap<>();
    private final Map<Long, String> cachedModels = new ConcurrentHashMap<>();

    /** 读写锁，保护 VectorStore 的线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 获取 VectorStore 实例
     * 如果配置未变更，返回缓存的实例
     * 如果配置变更，自动重建
     *
     * @return VectorStore 实例，未配置 API Key 时返回 null
     */
    public VectorStore getVectorStore() {
        Long tenantId = currentTenantId();
        // 读取当前配置
        String embeddingApiKey = getSettingValue(EMBEDDING_API_KEY_SETTING);
        String embeddingBaseUrl = getSettingValue(EMBEDDING_BASE_URL_SETTING);
        String embeddingModel = getSettingValue(EMBEDDING_MODEL_SETTING);

        // 如果 Embedding 专用 API Key 未配置，使用 AI 对话的 API Key
        if (embeddingApiKey == null || embeddingApiKey.trim().isEmpty()) {
            embeddingApiKey = getSettingValue(AI_API_KEY_SETTING);
        }

        // 如果 Embedding 专用 Base URL 未配置，使用 AI 对话的 Base URL
        if (embeddingBaseUrl == null || embeddingBaseUrl.trim().isEmpty()) {
            embeddingBaseUrl = getSettingValue(AI_BASE_URL_SETTING);
        }

        // API Key 未配置
        if (embeddingApiKey == null || embeddingApiKey.trim().isEmpty()) {
            if (!vectorStores.containsKey(tenantId)) {
                log.debug("[VectorStore] API Key 未配置，VectorStore 不可用");
            }
            return null;
        }

        String effectiveBaseUrl = (embeddingBaseUrl != null && !embeddingBaseUrl.trim().isEmpty())
                ? embeddingBaseUrl.trim() : DEFAULT_BASE_URL;
        String effectiveModel = (embeddingModel != null && !embeddingModel.trim().isEmpty())
                ? embeddingModel.trim() : DEFAULT_EMBEDDING_MODEL;

        // 检查配置是否变化，需要重建
        boolean needRebuild = !vectorStores.containsKey(tenantId)
                || !embeddingApiKey.equals(cachedApiKeys.get(tenantId))
                || !safeEquals(effectiveBaseUrl, cachedBaseUrls.get(tenantId))
                || !safeEquals(effectiveModel, cachedModels.get(tenantId));

        if (needRebuild) {
            lock.writeLock().lock();
            try {
                // 双重检查
                VectorStore currentStore = vectorStores.get(tenantId);
                boolean stillNeedRebuild = currentStore == null
                        || !embeddingApiKey.equals(cachedApiKeys.get(tenantId))
                        || !safeEquals(effectiveBaseUrl, cachedBaseUrls.get(tenantId))
                        || !safeEquals(effectiveModel, cachedModels.get(tenantId));

                if (stillNeedRebuild) {
                    log.info("[VectorStore] 检测到配置变化，重建 VectorStore: baseUrl={}, model={}",
                            effectiveBaseUrl, effectiveModel);

                    VectorStore rebuiltStore = buildVectorStore(tenantId, embeddingApiKey, effectiveBaseUrl, effectiveModel);
                    if (rebuiltStore != null) {
                        vectorStores.put(tenantId, rebuiltStore);
                    }
                    cachedApiKeys.put(tenantId, embeddingApiKey);
                    cachedBaseUrls.put(tenantId, effectiveBaseUrl);
                    cachedModels.put(tenantId, effectiveModel);

                    if (rebuiltStore != null) {
                        log.info("[VectorStore] VectorStore 重建成功");
                    }
                }
            } catch (Exception e) {
                log.error("[VectorStore] VectorStore 重建失败", e);
                vectorStores.remove(tenantId);
                cachedApiKeys.remove(tenantId);
            } finally {
                lock.writeLock().unlock();
            }
        }

        lock.readLock().lock();
        try {
            return vectorStores.get(tenantId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 强制重建 VectorStore（配置变更时调用）
     */
    public void forceRebuild() {
        log.info("[VectorStore] 收到强制重建信号，清除缓存");
        lock.writeLock().lock();
        try {
            Long tenantId = TenantContext.get();
            if (tenantId == null) {
                vectorStores.clear();
                cachedApiKeys.clear();
                cachedBaseUrls.clear();
                cachedModels.clear();
            } else {
                vectorStores.remove(tenantId);
                cachedApiKeys.remove(tenantId);
                cachedBaseUrls.remove(tenantId);
                cachedModels.remove(tenantId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 保存向量数据到文件
     */
    public void saveToFile() {
        Long tenantId = currentTenantId();
        VectorStore vectorStore = vectorStores.get(tenantId);
        if (vectorStore instanceof SimpleVectorStore) {
            try {
                String tenantFilePath = tenantVectorStorePath(tenantId);
                ((SimpleVectorStore) vectorStore).save(new File(tenantFilePath));
                log.debug("[VectorStore] 向量数据已保存到: {}", tenantFilePath);
            } catch (Exception e) {
                log.warn("[VectorStore] 保存向量数据失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 构建 VectorStore 实例
     */
    private VectorStore buildVectorStore(Long tenantId, String apiKey, String baseUrl, String model) {
        try {
            // 创建 OpenAiApi 实例
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .apiKey(new SimpleApiKey(apiKey.trim()))
                    .baseUrl(baseUrl)
                    .build();

            // 创建 EmbeddingOptions（指定模型）
            OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                    .model(model)
                    .build();

            // 创建 EmbeddingModel
            OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(
                    openAiApi, MetadataMode.EMBED, embeddingOptions);

            // 创建 SimpleVectorStore
            SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();

            // 尝试从文件加载已有数据
            String tenantFilePath = tenantVectorStorePath(tenantId);
            Path path = Paths.get(tenantFilePath);
            if (Files.exists(path)) {
                try {
                    simpleVectorStore.load(new File(tenantFilePath));
                    log.info("[VectorStore] 成功从文件加载向量数据: {}", tenantFilePath);
                } catch (Exception e) {
                    log.warn("[VectorStore] 加载向量数据失败，将创建新的向量存储: {}", e.getMessage());
                }
            } else {
                // 确保父目录存在
                try {
                    Files.createDirectories(path.getParent());
                    log.info("[VectorStore] 创建向量存储目录: {}", path.getParent());
                } catch (Exception e) {
                    log.warn("[VectorStore] 创建目录失败: {}", e.getMessage());
                }
            }

            return simpleVectorStore;

        } catch (Exception e) {
            log.error("[VectorStore] 创建 VectorStore 失败", e);
            return null;
        }
    }

    private String getSettingValue(String key) {
        try {
            return sysSettingService.getSettingValue(key);
        } catch (Exception e) {
            log.warn("[VectorStore] 读取配置失败: key={}", key, e);
            return null;
        }
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? 0L : tenantId;
    }

    private String tenantVectorStorePath(Long tenantId) {
        Path configuredPath = Paths.get(vectorStoreFilePath);
        String fileName = configuredPath.getFileName() == null ? "vectorstore.json" : configuredPath.getFileName().toString();
        Path parent = configuredPath.getParent() == null ? Paths.get("dbdata") : configuredPath.getParent();
        return parent.resolve("tenant-" + tenantId).resolve(fileName).toString();
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
