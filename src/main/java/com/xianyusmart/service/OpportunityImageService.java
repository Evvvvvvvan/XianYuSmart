package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.config.rag.AIEndpointResolver;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 商机商品图生成服务
 */
@Service
public class OpportunityImageService {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_IMAGE_MODEL = "wanx2.1-t2i-turbo";

    private final SysSettingService settingService;
    private final ImageUploadService imageUploadService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpportunityImageService(SysSettingService settingService,
                                   ImageUploadService imageUploadService,
                                   ObjectMapper objectMapper) {
        this.settingService = settingService;
        this.imageUploadService = imageUploadService;
        this.objectMapper = objectMapper;
    }

    public String generate(Long accountId, String prompt) {
        if (!isEnabled(settingService.getSettingValue("ai_image_enabled"))) {
            throw new IllegalStateException("AI商品图未启用，请先在系统设置的高级配置中启用");
        }
        String apiKey = text(settingService.getSettingValue("ai_image_api_key"));
        if (apiKey.isBlank()) {
            throw new IllegalStateException("AI商品图 API Key未配置");
        }
        String baseUrl = text(settingService.getSettingValue("ai_image_base_url"));
        String model = text(settingService.getSettingValue("ai_image_model"));
        Map<String, Object> body = Map.of(
                "model", model.isBlank() ? DEFAULT_IMAGE_MODEL : model,
                "prompt", prompt,
                "size", "1024x1024",
                "n", 1,
                "response_format", "b64_json"
        );
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(imageEndpoint(baseUrl)))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("AI生图请求构建失败", e);
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AI生图服务返回异常: HTTP " + response.statusCode());
            }
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() { });
            List<?> images = payload.get("data") instanceof List<?> list ? list : List.of();
            if (images.isEmpty() || !(images.get(0) instanceof Map<?, ?> image)) {
                throw new IllegalStateException("AI生图服务未返回图片");
            }
            String base64 = text(image.get("b64_json"));
            ResultObject<String> upload = base64.isBlank()
                    ? imageUploadService.uploadImageFromUrl(accountId, text(image.get("url")))
                    : imageUploadService.uploadImage(accountId, Base64.getDecoder().decode(base64),
                    "ai-opportunity.png");
            if (upload.getCode() != 200 || upload.getData() == null || upload.getData().isBlank()) {
                throw new IllegalStateException("AI商品图上传失败: " + upload.getMsg());
            }
            return upload.getData();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI生图请求已中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI生图失败: " + e.getMessage(), e);
        }
    }

    static String imageEndpoint(String configuredBaseUrl) {
        String baseUrl = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? DEFAULT_BASE_URL : configuredBaseUrl.trim();
        return AIEndpointResolver.resolve(baseUrl, AIEndpointResolver.Capability.IMAGE).endpoint();
    }

    private boolean isEnabled(String value) {
        return "1".equals(text(value)) || Boolean.parseBoolean(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
