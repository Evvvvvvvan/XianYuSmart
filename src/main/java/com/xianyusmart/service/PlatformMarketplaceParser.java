package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台搜索与发布响应解析
 */
class PlatformMarketplaceParser {

    private final ObjectMapper objectMapper;

    PlatformMarketplaceParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<Map<String, Object>> parseSearchResponse(String response, int limit) {
        Map<String, Object> root = readMap(response);
        Map<String, Object> data = map(root.get("data"));
        List<?> records = findList(data, Set.of("resultList", "cardList", "items"));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Object recordValue : records) {
            Map<String, Object> record = map(recordValue);
            Map<String, Object> card = firstNonEmptyMap(record.get("cardData"), record.get("data"), record);
            String itemId = firstText(card, "itemId", "id", "idleItemId");
            if (itemId.isBlank() || !itemIds.add(itemId)) {
                continue;
            }
            String title = nestedText(card, "titleSummary", "text");
            if (title.isBlank()) {
                title = firstText(card, "title", "desc");
            }
            String image = nestedText(card, "mainPicInfo", "url");
            if (image.isBlank()) {
                image = firstText(card, "mainPicUrl", "picUrl", "image");
            }
            Map<String, Object> priceInfo = map(card.get("priceInfo"));
            String price = firstText(priceInfo, "price", "priceText", "value");
            Map<String, Object> user = map(card.get("user"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", itemId);
            item.put("title", title.isBlank() ? "商品 " + itemId : title);
            item.put("sourceUrl", "https://www.goofish.com/item?id=" + itemId);
            item.put("price", price);
            item.put("images", image.isBlank() ? List.of() : List.of(https(image)));
            item.put("sellerNick", firstText(user, "userNick", "nick", "nickname"));
            item.put("sellerAvatar", https(firstText(user, "avatar", "logo")));
            result.add(item);
            if (result.size() >= limit) {
                break;
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("平台搜索没有返回商品，请检查关键词、账号状态或完成平台验证后重试");
        }
        return result;
    }

    String extractPublishedItemId(String response) {
        Map<String, Object> data = map(readMap(response).get("data"));
        String itemId = firstText(data, "itemId", "itemIdStr", "idleItemId");
        if (!itemId.isBlank()) {
            return itemId;
        }
        return firstText(map(data.get("data")), "itemId", "itemIdStr", "idleItemId");
    }

    Map<String, Object> extractDefaultAddress(String response) {
        return findAddress(map(readMap(response).get("data")));
    }

    private Map<String, Object> findAddress(Object value) {
        if (value instanceof List<?> list) {
            for (Object child : list) {
                Map<String, Object> found = findAddress(child);
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> current = map(source);
        if (!firstText(current, "divisionId").isBlank()
                && (!firstText(current, "prov", "province").isBlank()
                || !firstText(current, "city").isBlank())) {
            return current;
        }
        for (Object child : source.values()) {
            Map<String, Object> found = findAddress(child);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return Map.of();
    }

    private List<?> findList(Object value, Set<String> keys) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (!(value instanceof Map<?, ?> source)) {
            return List.of();
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (keys.contains(String.valueOf(entry.getKey())) && entry.getValue() instanceof List<?> list) {
                return list;
            }
        }
        for (Object child : source.values()) {
            List<?> found = findList(child, keys);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private Map<String, Object> firstNonEmptyMap(Object... values) {
        for (Object value : values) {
            Map<String, Object> candidate = map(value);
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return Map.of();
    }

    private String nestedText(Map<String, Object> source, String objectKey, String valueKey) {
        return text(map(source.get(objectKey)).get(valueKey));
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("平台返回内容无法解析", e);
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String https(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return value.startsWith("http://") ? "https://" + value.substring(7) : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
