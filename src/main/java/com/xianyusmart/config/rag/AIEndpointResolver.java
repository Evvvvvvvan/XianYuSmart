package com.xianyusmart.config.rag;

import java.net.URI;
import java.util.Locale;

/**
 * AI接口地址解析器
 */
public final class AIEndpointResolver {

    private AIEndpointResolver() {
    }

    public enum Capability {
        OPENAI_CHAT("/chat/completions"),
        ANTHROPIC_CHAT("/messages"),
        EMBEDDING("/embeddings"),
        IMAGE("/images/generations");

        private final String endpointSuffix;

        Capability(String endpointSuffix) {
            this.endpointSuffix = endpointSuffix;
        }
    }

    public record Endpoint(String baseUrl, String path, String endpoint) {
    }

    /**
     * 统一兼容根地址、版本地址和完整接口地址，避免重复拼接版本路径。
     */
    public static Endpoint resolve(String configuredBaseUrl, Capability capability) {
        if (capability == null) {
            throw new IllegalArgumentException("AI接口能力不能为空");
        }

        URI uri = parseBaseUrl(configuredBaseUrl);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String baseUrl = scheme + "://" + uri.getRawAuthority();
        String path = normalizePath(uri.getRawPath(), capability);
        return new Endpoint(baseUrl, path, baseUrl + path);
    }

    /**
     * 校验配置地址的公共安全边界，保存配置和实际请求保持一致。
     */
    public static void validateBaseUrl(String configuredBaseUrl) {
        parseBaseUrl(configuredBaseUrl);
    }

    private static URI parseBaseUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("API Base URL不能为空");
        }

        URI uri;
        try {
            uri = URI.create(configuredBaseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("API Base URL格式不正确", e);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException("API Base URL仅支持http或https地址");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("API Base URL不能包含用户名或密码");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("API Base URL不能包含查询参数或片段");
        }
        return uri;
    }

    private static String normalizePath(String rawPath, Capability capability) {
        String path = rawPath == null ? "" : rawPath.trim().replaceAll("/+$", "");
        if (path.isEmpty()) {
            return "/v1" + capability.endpointSuffix;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(capability.endpointSuffix)) {
            return path;
        }
        if (isVersionedBase(lowerPath)) {
            return path + capability.endpointSuffix;
        }
        return path + "/v1" + capability.endpointSuffix;
    }

    private static boolean isVersionedBase(String path) {
        return path.matches(".*\\/v\\d+(?:beta\\d*)?$") || path.endsWith("/openai");
    }
}
