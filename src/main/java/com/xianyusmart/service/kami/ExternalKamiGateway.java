package com.xianyusmart.service.kami;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.service.notification.PinnedHttpsClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部卡密 HTTP 网关
 */
@Component
public class ExternalKamiGateway {

    private final ObjectMapper objectMapper;
    private final PinnedHttpsClient httpsClient;

    public ExternalKamiGateway(ObjectMapper objectMapper, PinnedHttpsClient httpsClient) {
        this.objectMapper = objectMapper;
        this.httpsClient = httpsClient;
    }

    public String request(XianyuKamiConfig config, String orderId, int quantity, String requestToken) {
        try {
            String body = replaceVariables(config.getExternalApiBody(), orderId, quantity, requestToken);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", "XianYuSmart-External-Supply/2");
            headers.put("Idempotency-Key", requestToken);
            for (Map.Entry<String, String> header : parseHeaders(config.getExternalApiHeaders()).entrySet()) {
                headers.put(header.getKey(), replaceVariables(
                        header.getValue(), orderId, quantity, requestToken));
            }
            PinnedHttpsClient.Response response = httpsClient.post(
                    config.getExternalApiUrl(), headers, body,
                    Duration.ofSeconds(config.getExternalApiTimeoutSeconds()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ExternalKamiException("外部卡密接口返回 HTTP " + response.statusCode(), true);
            }
            return response.body();
        } catch (ExternalKamiException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalKamiException("外部卡密请求结果不确定: " + e.getMessage(), true);
        }
    }

    private Map<String, String> parseHeaders(String value) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return headers;
        }
        JsonNode node = objectMapper.readTree(value);
        if (!node.isObject()) {
            throw new IllegalArgumentException("外部接口请求头必须是 JSON 对象");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey().trim();
            String headerValue = field.getValue().asText();
            if (name.isEmpty() || name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")
                    || name.equalsIgnoreCase("idempotency-key")) {
                continue;
            }
            headers.put(name, headerValue);
        }
        return headers;
    }

    private String replaceVariables(String value, String orderId, int quantity, String requestToken) {
        return (value == null ? "{}" : value)
                .replace("{orderId}", orderId)
                .replace("{quantity}", String.valueOf(quantity))
                .replace("{requestToken}", requestToken);
    }

    public static class ExternalKamiException extends RuntimeException {

        private final boolean uncertain;

        public ExternalKamiException(String message, boolean uncertain) {
            super(message);
            this.uncertain = uncertain;
        }

        public boolean isUncertain() {
            return uncertain;
        }
    }
}
