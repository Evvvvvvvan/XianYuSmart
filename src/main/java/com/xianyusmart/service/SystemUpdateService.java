package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.controller.dto.VersionInfoRespDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本检测与安全更新请求
 */
@Service
public class SystemUpdateService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.version:2.0.0}")
    private String currentVersion;

    @Value("${app.update.release-api:}")
    private String releaseApi;

    @Value("${app.update.request-dir:/app/update}")
    private String updateRequestDir;

    public SystemUpdateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public VersionInfoRespDTO checkUpdate() {
        VersionInfoRespDTO result = new VersionInfoRespDTO();
        result.setCurrentVersion(currentVersion);
        result.setLatestVersion(currentVersion);
        result.setHasUpdate(false);
        if (releaseApi == null || releaseApi.isBlank()) {
            return result;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(releaseApi))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "XianYuSmart")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("版本服务返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String latestVersion = normalizeVersion(root.path("tag_name").asText(""));
            result.setLatestVersion(latestVersion);
            result.setHasUpdate(compareVersion(latestVersion, currentVersion) > 0);
            result.setUpdateContent(root.path("body").asText(""));
            result.setPublishedAt(root.path("published_at").asText(""));
            result.setDownloadUrl(root.path("html_url").asText(""));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("检查更新失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> requestUpdate() {
        VersionInfoRespDTO version = checkUpdate();
        if (!Boolean.TRUE.equals(version.getHasUpdate())) {
            throw new IllegalStateException("当前已经是最新版本");
        }
        try {
            Path directory = Path.of(updateRequestDir);
            Files.createDirectories(directory);
            if (!Files.isWritable(directory) || !Files.exists(directory.resolve("agent.ready"))) {
                throw new IllegalStateException("自动更新代理未就绪");
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("version", version.getLatestVersion());
            request.put("requestedAt", Instant.now().toString());
            byte[] content = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            Path temporary = directory.resolve("request.tmp");
            Path target = directory.resolve("request.json");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Map.of("version", version.getLatestVersion(), "status", "REQUESTED");
        } catch (Exception e) {
            throw new IllegalStateException("提交自动更新失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> updateAgentStatus() {
        Path directory = Path.of(updateRequestDir);
        return Map.of(
                "available", Files.isDirectory(directory) && Files.isWritable(directory)
                        && Files.exists(directory.resolve("agent.ready")),
                "requestPending", Files.exists(directory.resolve("request.json"))
        );
    }

    public static int compareVersion(String first, String second) {
        String[] firstParts = normalizeVersion(first).split("\\.");
        String[] secondParts = normalizeVersion(second).split("\\.");
        int length = Math.max(firstParts.length, secondParts.length);
        for (int i = 0; i < length; i++) {
            int firstNumber = i < firstParts.length ? parseNumber(firstParts[i]) : 0;
            int secondNumber = i < secondParts.length ? parseNumber(secondParts[i]) : 0;
            if (firstNumber != secondNumber) {
                return Integer.compare(firstNumber, secondNumber);
            }
        }
        return 0;
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        return version.trim().replaceFirst("^[vV]\\.?", "").split("-", 2)[0];
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
