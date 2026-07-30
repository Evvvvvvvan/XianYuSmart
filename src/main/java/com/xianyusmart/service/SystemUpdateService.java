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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 版本检测与安全更新请求
 */
@Service
public class SystemUpdateService {

    private static final String DEFAULT_RELEASE_API =
            "https://api.github.com/repos/Evvvvvvvan/XianYuSmart/releases/latest";
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "REQUESTED", "CHECKING", "DOWNLOADING", "VERIFYING",
            "INSTALLING", "RESTARTING", "HEALTH_CHECKING");
    private static final Duration STALE_TASK_TIMEOUT = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.version:2.0.3}")
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
        try {
            String endpoint = releaseApi == null || releaseApi.isBlank() ? DEFAULT_RELEASE_API : releaseApi;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
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

    public synchronized Map<String, Object> requestUpdate() {
        Path directory = Path.of(updateRequestDir);
        Map<String, Object> currentStatus = updateAgentStatus();
        if (Boolean.TRUE.equals(currentStatus.get("active"))
                || Files.exists(directory.resolve("request.json"))) {
            return currentStatus;
        }
        VersionInfoRespDTO version = checkUpdate();
        if (!Boolean.TRUE.equals(version.getHasUpdate())) {
            throw new IllegalStateException("当前已经是最新版本");
        }
        try {
            Files.createDirectories(directory);
            if (!Files.isWritable(directory) || !Files.exists(directory.resolve("agent.ready"))) {
                throw new IllegalStateException("自动更新代理未就绪");
            }
            Map<String, Object> request = new LinkedHashMap<>();
            String taskId = UUID.randomUUID().toString();
            String requestedAt = Instant.now().toString();
            request.put("taskId", taskId);
            request.put("version", version.getLatestVersion());
            request.put("requestedAt", requestedAt);

            Map<String, Object> status = new LinkedHashMap<>(request);
            status.put("status", "REQUESTED");
            status.put("progress", 0);
            status.put("message", "更新任务已提交，等待更新代理处理");
            status.put("downloadedBytes", 0L);
            status.put("totalBytes", 0L);
            status.put("updatedAt", requestedAt);

            // 状态先于请求落盘，路径监听器触发后可以立即读取完整任务信息
            writeJsonAtomically(directory.resolve("status.json"), status);
            writeJsonAtomically(directory.resolve("request.json"), request);
            return updateAgentStatus();
        } catch (Exception e) {
            throw new IllegalStateException("提交自动更新失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> updateAgentStatus() {
        Path directory = Path.of(updateRequestDir);
        boolean available = Files.isDirectory(directory) && Files.isWritable(directory)
                && Files.exists(directory.resolve("agent.ready"));
        boolean requestPending = Files.exists(directory.resolve("request.json"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("requestPending", requestPending);
        try {
            Map<String, Object> status = readJson(directory.resolve("status.json"));
            Map<String, Object> request = readJson(directory.resolve("request.json"));
            if (status.isEmpty() && !request.isEmpty()) {
                status = new LinkedHashMap<>(request);
                status.put("status", "REQUESTED");
                status.put("progress", 0);
                status.put("message", "更新任务已提交，等待更新代理处理");
                status.put("downloadedBytes", 0L);
                status.put("totalBytes", 0L);
                status.put("updatedAt", request.get("requestedAt"));
            }
            result.putAll(status);
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("progress", 0);
            result.put("message", "更新状态文件无法读取，可重新提交更新");
        }

        String status = String.valueOf(result.getOrDefault("status", "IDLE"));
        if (requestPending && ACTIVE_STATUSES.contains(status)
                && isStale(result.get("updatedAt"))
                && deleteStaleRequest(directory.resolve("request.json"))) {
            requestPending = false;
            result.put("requestPending", false);
            status = "FAILED";
            result.put("message", "更新任务长时间无进展，已允许重新尝试");
        }
        if (ACTIVE_STATUSES.contains(status) && !requestPending) {
            status = "FAILED";
            result.put("message", "更新任务已中断，可重新尝试");
        }
        result.put("status", status);
        result.putIfAbsent("progress", 0);
        result.putIfAbsent("message", available ? "暂无更新任务" : "自动更新代理未就绪");
        result.putIfAbsent("downloadedBytes", 0L);
        result.putIfAbsent("totalBytes", 0L);
        result.put("active", requestPending || ACTIVE_STATUSES.contains(status));
        result.put("canRetry", "FAILED".equals(status) && !requestPending);
        return result;
    }

    private boolean isStale(Object updatedAt) {
        if (updatedAt == null) {
            return false;
        }
        try {
            return Instant.parse(String.valueOf(updatedAt))
                    .plus(STALE_TASK_TIMEOUT)
                    .isBefore(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean deleteStaleRequest(Path request) {
        try {
            return Files.deleteIfExists(request);
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path path) throws Exception {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    private void writeJsonAtomically(Path target, Map<String, Object> content) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, objectMapper.writeValueAsString(content), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
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
