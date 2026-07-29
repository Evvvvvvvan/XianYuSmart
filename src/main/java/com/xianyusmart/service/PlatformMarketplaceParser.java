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
        return parseSearchPageResponse(response, limit, false).items();
    }

    SearchPage parseSearchPageResponse(String response, int limit, boolean allowEmpty) {
        Map<String, Object> root = readMap(response);
        Map<String, Object> data = map(root.get("data"));
        List<?> records = findList(data, Set.of("resultList", "cardList", "items"));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Object recordValue : records) {
            Map<String, Object> record = map(recordValue);
            // PC 搜索商品主体嵌套在 data.item.main，保留旧结构回退以兼容历史响应。
            Map<String, Object> card = locateSearchCard(record);
            Map<String, Object> exContent = map(card.get("exContent"));
            Map<String, Object> detailParams = map(exContent.get("detailParams"));
            Map<String, Object> clickArgs = map(map(card.get("clickParam")).get("args"));
            String itemId = firstNonBlank(
                    firstText(exContent, "itemId", "id", "idleItemId"),
                    firstText(clickArgs, "item_id", "itemId", "id"),
                    firstText(card, "itemId", "id", "idleItemId"));
            if (itemId.isBlank() || !itemIds.add(itemId)) {
                continue;
            }
            String title = firstNonBlank(
                    firstText(detailParams, "title", "desc"),
                    firstText(exContent, "title", "desc"));
            if (title.isBlank()) {
                title = nestedText(card, "titleSummary", "text");
            }
            if (title.isBlank()) {
                title = firstText(card, "title", "desc");
            }
            String image = firstText(exContent, "picUrl", "mainPicUrl", "image");
            if (image.isBlank()) {
                image = nestedText(card, "mainPicInfo", "url");
            }
            if (image.isBlank()) {
                image = firstText(card, "mainPicUrl", "picUrl", "image");
            }
            Map<String, Object> priceInfo = map(card.get("priceInfo"));
            String price = firstNonBlank(
                    firstText(clickArgs, "price", "displayPrice"),
                    firstText(detailParams, "soldPrice", "price"),
                    firstText(priceInfo, "price", "priceText", "value"),
                    priceText(exContent.get("price")));
            Map<String, Object> user = map(card.get("user"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", itemId);
            item.put("title", title.isBlank() ? "商品 " + itemId : title);
            item.put("sourceUrl", "https://www.goofish.com/item?id=" + itemId);
            item.put("price", price);
            item.put("images", image.isBlank() ? List.of() : List.of(https(image)));
            item.put("sellerNick", firstNonBlank(
                    firstText(detailParams, "userNick", "userNickName", "nickname"),
                    firstText(exContent, "userNickName", "userNick", "nickname"),
                    firstText(user, "userNick", "nick", "nickname")));
            item.put("sellerId", firstNonBlank(
                    firstText(detailParams, "userId", "sellerId"),
                    firstText(exContent, "userId", "sellerId"),
                    firstText(user, "userId", "sellerId")));
            item.put("sellerAvatar", https(firstNonBlank(
                    firstText(exContent, "userAvatarUrl", "avatar"),
                    firstText(user, "avatar", "logo"))));
            // 搜索卡片只从卖家对象和明确的卖家字段读取口碑，避免把商品热度误识别为卖家评价。
            Map<String, Object> seller = firstNonEmptyMap(
                    card.get("seller"), card.get("sellerDO"), exContent.get("seller"),
                    detailParams.get("seller"), user);
            putSellerFacts(item, seller);
            putSearchSellerFacts(item, detailParams, exContent);
            result.add(item);
            if (result.size() >= limit) {
                break;
            }
        }
        if (result.isEmpty() && !allowEmpty) {
            throw new IllegalStateException("平台搜索没有返回商品，请检查关键词、账号状态或完成平台验证后重试");
        }
        Map<String, Object> resultInfo = map(data.get("resultInfo"));
        boolean hasMore = booleanValue(resultInfo.get("hasNextPage"));
        long total = longValue(resultInfo.get("numFound"));
        return new SearchPage(result, hasMore, total);
    }

    record SearchPage(List<Map<String, Object>> items, boolean hasMore, long total) { }

    SearchPage parseShopPageResponse(String response, int limit) {
        Map<String, Object> data = map(readMap(response).get("data"));
        List<?> cards = findList(data, Set.of("cardList"));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Object cardValue : cards) {
            Map<String, Object> card = map(cardValue);
            Map<String, Object> cardData = firstNonEmptyMap(card.get("cardData"), card);
            Map<String, Object> detailParams = map(cardData.get("detailParams"));
            String itemId = firstNonBlank(
                    firstText(cardData, "id", "itemId"),
                    firstText(detailParams, "itemId", "id"));
            if (itemId.isBlank() || !itemIds.add(itemId)) {
                continue;
            }
            Map<String, Object> priceInfo = map(cardData.get("priceInfo"));
            Map<String, Object> picInfo = map(cardData.get("picInfo"));
            String image = firstNonBlank(
                    firstText(picInfo, "picUrl", "url"),
                    firstText(detailParams, "picUrl", "picUrlNew"),
                    firstText(cardData, "coverPic", "imageUrl", "mainPicUrl"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", itemId);
            item.put("title", firstNonBlank(
                    firstText(cardData, "title", "itemName"),
                    firstText(detailParams, "title")));
            item.put("sourceUrl", "https://www.goofish.com/item?id=" + itemId);
            item.put("price", firstNonBlank(
                    firstText(priceInfo, "price", "value"),
                    firstText(detailParams, "soldPrice"),
                    firstText(cardData, "price", "soldPrice")));
            item.put("images", image.isBlank() ? List.of() : List.of(https(image)));
            result.add(item);
            if (result.size() >= limit) {
                break;
            }
        }
        long total = firstLong(data, "totalCount", "total", "numFound");
        return new SearchPage(result, result.size() >= limit, total);
    }

    Map<String, Object> parseItemDetailResponse(String response, String itemId) {
        Map<String, Object> data = map(readMap(response).get("data"));
        Map<String, Object> item = firstNonEmptyMap(data.get("itemDO"), data.get("item"), data);
        Map<String, Object> seller = firstNonEmptyMap(data.get("sellerDO"), item.get("sellerDO"), item.get("seller"));
        List<?> imageInfos = findList(firstNonEmptyMap(data, item), Set.of("imageInfos", "images"));
        List<String> images = new ArrayList<>();
        for (Object imageValue : imageInfos) {
            String image = imageValue instanceof Map<?, ?>
                    ? firstText(map(imageValue), "url", "imageUrl", "picUrl")
                    : text(imageValue);
            if (!image.isBlank()) {
                images.add(https(image));
            }
        }
        String title = firstText(item, "title", "desc");
        if (title.isBlank()) {
            throw new IllegalStateException("平台商品详情缺少标题");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", itemId);
        result.put("title", title);
        result.put("description", firstText(item, "desc", "description"));
        result.put("price", firstText(item, "soldPrice", "price", "originalPrice"));
        result.put("images", images);
        result.put("sourceUrl", "https://www.goofish.com/item?id=" + itemId);
        String sellerId = firstText(seller, "sellerId", "userId");
        result.put("sellerId", sellerId);
        result.put("sellerNick", firstText(seller, "nick", "userNick", "nickname"));
        result.put("sellerAvatar", https(firstText(seller, "portraitUrl", "avatar", "logo")));
        if (!sellerId.isBlank()) {
            result.put("sellerProfileUrl", "https://www.goofish.com/personal?userId=" + sellerId);
        }
        putSellerFacts(result, seller);
        return result;
    }

    private void putSellerFacts(Map<String, Object> target, Object source) {
        // 商品详情中的卖家信用与历史口碑是公开事实，缺失时不推测数据。
        putTextIfPresent(target, "sellerCredit", recursiveText(source, Set.of(
                "sellerCredit", "sellerCreditLevel", "sellerZhimaCredit", "sellerZhimaLevel",
                "zhimaCredit", "zhimaCreditLevel", "zhimaLevel", "zhimaLevelInfo")));
        putTextIfPresent(target, "buyerCredit", recursiveText(source, Set.of(
                "buyerCredit", "buyerCreditLevel", "buyerZhimaCredit", "buyerZhimaLevel")));
        putLongIfPresent(target, "sellerPositiveCount", recursiveLong(source, Set.of(
                "sellerPositiveCount", "positiveRateCount", "positiveCount", "goodRateCount",
                "goodCount", "receivedPositiveCount", "goodRateNum", "sellerGoodRemarkCnt")));
        putLongIfPresent(target, "sellerNeutralCount", recursiveLong(source, Set.of(
                "sellerNeutralCount", "neutralRateCount", "neutralCount", "defaultRateCount",
                "defaultCount", "sellerDefaultRemarkCnt")));
        putLongIfPresent(target, "sellerNegativeCount", recursiveLong(source, Set.of(
                "sellerNegativeCount", "negativeRateCount", "negativeCount", "badRateCount",
                "badCount", "receivedNegativeCount", "badRateNum", "sellerBadRemarkCnt")));
    }

    @SafeVarargs
    private final void putSearchSellerFacts(Map<String, Object> target, Map<String, Object>... sources) {
        for (Map<String, Object> source : sources) {
            putTextIfPresent(target, "sellerCredit", firstText(source,
                    "sellerCredit", "sellerCreditLevel", "sellerZhimaCredit", "sellerZhimaLevel"));
            putLongIfPresent(target, "sellerPositiveCount", directLong(source,
                    "sellerPositiveCount", "sellerGoodRemarkCnt", "sellerPositiveRateCount"));
            putLongIfPresent(target, "sellerNeutralCount", directLong(source,
                    "sellerNeutralCount", "sellerDefaultRemarkCnt", "sellerNeutralRateCount"));
            putLongIfPresent(target, "sellerNegativeCount", directLong(source,
                    "sellerNegativeCount", "sellerBadRemarkCnt", "sellerNegativeRateCount"));
        }
    }

    private Long directLong(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            try {
                return Long.valueOf(String.valueOf(value).replaceAll("[^0-9-]", ""));
            } catch (NumberFormatException ignored) {
                // 当前字段格式异常时继续读取下一个平台兼容字段。
            }
        }
        return null;
    }

    private void putTextIfPresent(Map<String, Object> target, String key, String value) {
        if (!value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putLongIfPresent(Map<String, Object> target, String key, Long value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String recursiveText(Object source, Set<String> keys) {
        Object value = recursiveValue(source, keys);
        if (value instanceof Map<?, ?> details) {
            return firstText(map(details), "text", "value", "level", "levelName", "displayName", "title", "description");
        }
        return text(value);
    }

    private Long recursiveLong(Object source, Set<String> keys) {
        Object value = recursiveValue(source, keys);
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).replaceAll("[^0-9-]", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object recursiveValue(Object source, Set<String> keys) {
        if (source instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (keys.contains(String.valueOf(entry.getKey())) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
            for (Object child : values.values()) {
                Object found = recursiveValue(child, keys);
                if (found != null) {
                    return found;
                }
            }
        } else if (source instanceof List<?> values) {
            for (Object child : values) {
                Object found = recursiveValue(child, keys);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Map<String, Object> locateSearchCard(Map<String, Object> record) {
        Map<String, Object> data = map(record.get("data"));
        Map<String, Object> item = map(data.get("item"));
        return firstNonEmptyMap(
                map(item.get("main")),
                map(data.get("main")),
                record.get("cardData"),
                item,
                data,
                record);
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
        if (!firstText(current, "divisionId", "addressDivisionId", "adcode").isBlank()
                && (!firstText(current, "prov", "province", "addressProv", "pname").isBlank()
                || !firstText(current, "city", "addressCity", "cityname").isBlank())) {
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

    private String priceText(Object value) {
        if (value instanceof Map<?, ?>) {
            return firstText(map(value), "value", "text", "price");
        }
        return text(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value)
                || "1".equals(String.valueOf(value))
                || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private long longValue(Object value) {
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long firstLong(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            long value = longValue(source.get(key));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }
}
