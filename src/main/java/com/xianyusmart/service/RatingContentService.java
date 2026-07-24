package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuGoodsConfig;
import com.xianyusmart.entity.XianyuGoodsOrder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 自动评价文案池服务
 */
@Service
public class RatingContentService {

    private static final int MAX_CONTENT_COUNT = 10;
    private static final int MAX_CONTENT_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public RatingContentService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalizeForStorage(String content) {
        try {
            return objectMapper.writeValueAsString(parseContents(content));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("评价文案格式不正确");
        }
    }

    public String resolve(String content, XianyuGoodsOrder order) {
        List<String> contents = parseContents(content);
        String orderId = order == null ? "" : safeValue(order.getOrderId());
        String template = contents.get(Math.floorMod(orderId.hashCode(), contents.size()));
        return renderTemplate(template, order);
    }

    public String renderTemplate(String template, XianyuGoodsOrder order) {
        String normalized = template == null ? "" : template.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("评价内容长度应为1至500个字符");
        }
        String resolved = normalized
                .replace("{buyerName}", order == null ? "" : safeValue(order.getBuyerUserName()))
                .replace("{goodsName}", order == null ? "" : safeValue(order.getGoodsTitle()))
                .replace("{orderId}", order == null ? "" : safeValue(order.getOrderId()));
        if (resolved.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("评价内容替换变量后不能超过500个字符");
        }
        return resolved;
    }

    private List<String> parseContents(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            normalized = XianyuGoodsConfig.DEFAULT_AUTO_RATE_CONTENT;
        }

        List<String> source = readJsonContents(normalized);
        if (source == null) {
            source = List.of(normalized);
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : source) {
            String item = value == null ? "" : value.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (item.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException("单条评价文案不能超过500个字符");
            }
            unique.add(item);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("至少需要一条评价文案");
        }
        if (unique.size() > MAX_CONTENT_COUNT) {
            throw new IllegalArgumentException("评价文案最多配置10条");
        }
        return new ArrayList<>(unique);
    }

    private List<String> readJsonContents(String content) {
        if (!content.startsWith("[")) {
            return null;
        }
        try {
            return objectMapper.readValue(content, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }
}
