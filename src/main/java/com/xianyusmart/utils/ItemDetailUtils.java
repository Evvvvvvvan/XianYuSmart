package com.xianyusmart.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class ItemDetailUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 平台不同入口返回的SKU数组字段名。 */
    private static final List<String> SKU_LIST_FIELDS = List.of(
            "skuList", "idleItemSkuList", "skuInfoList", "itemSkuList", "skuDTOList");
    /** SKU编号字段，平台在部分响应里改用 id 或 skuCode。 */
    private static final List<String> SKU_ID_FIELDS = List.of("skuId", "sku_id", "id", "skuCode");
    /** 规格组合字段，新旧版本分别使用 propertyList / properties 等命名。 */
    private static final List<String> SKU_PROPERTY_FIELDS = List.of(
            "propertyList", "skuPropertyList", "properties", "propertyListDTOList");
    private static final List<String> PROPERTY_ID_FIELDS = List.of("propertyId", "propId", "pid");
    private static final List<String> PROPERTY_TEXT_FIELDS = List.of("propertyText", "propertyName", "property");
    private static final List<String> VALUE_ID_FIELDS = List.of("valueId", "valId", "vid");
    private static final List<String> VALUE_TEXT_FIELDS = List.of("valueText", "valueName", "value");

    public static String extractDescFromDetailJson(String detailJson) {
        if (detailJson == null || detailJson.isEmpty()) {
            return detailJson;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(detailJson);

            JsonNode itemDONode = findItemNode(rootNode);

            if (itemDONode != null && !itemDONode.isNull()) {
                JsonNode descNode = itemDONode.get("desc");
                if (descNode != null && !descNode.isNull()) {
                    String desc = descNode.asText();
                    log.info("成功提取desc字段，长度: {}", desc.length());
                    return desc;
                } else {
                    log.warn("itemDO中未找到desc字段");
                }
            } else {
                log.warn("未找到itemDO字段");
            }

            log.warn("无法提取desc字段，返回原始JSON，长度: {}", detailJson.length());
            return detailJson;
        } catch (Exception e) {
            log.error("解析商品详情JSON失败，返回原始JSON: {}", e.getMessage());
            return detailJson;
        }
    }

    public static List<XianyuGoodsSku> extractSkuList(String detailJson) {
        List<XianyuGoodsSku> result = new ArrayList<>();
        if (detailJson == null || detailJson.isEmpty()) {
            return result;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(detailJson);

            JsonNode itemDONode = findItemNode(rootNode);

            if (itemDONode == null || itemDONode.isNull()) {
                log.warn("未找到itemDO字段，无法解析SKU");
                return result;
            }

            JsonNode skuListNode = findSkuListNode(itemDONode);

            if (skuListNode == null || !skuListNode.isArray() || skuListNode.size() == 0) {
                log.info("商品无SKU数据");
                return result;
            }

            Map<String, XianyuGoodsSku> deduplicated = new LinkedHashMap<>();
            for (JsonNode skuNode : skuListNode) {
                if (!skuNode.isObject()) {
                    continue;
                }
                XianyuGoodsSku sku = new XianyuGoodsSku();

                String skuId = textOf(skuNode, SKU_ID_FIELDS);
                if (skuId.isEmpty()) {
                    // 缺少SKU编号时保存会出现唯一键冲突，直接跳过并提示真实字段，便于补充解析规则。
                    log.warn("SKU缺少编号字段，已跳过: fields={}", fieldNames(skuNode));
                    continue;
                }
                sku.setSkuId(skuId);

                JsonNode priceNode = skuNode.hasNonNull("priceInCent")
                        ? skuNode.get("priceInCent") : skuNode.get("price");
                if (priceNode != null && !priceNode.isNull()) {
                    sku.setPrice(priceOf(priceNode));
                }
                int quantity = intOf(skuNode, -1,
                        "quantity", "stock", "inventory", "quantityInStock", "stockNum");
                if (quantity >= 0) {
                    sku.setQuantity(quantity);
                }

                JsonNode propertyListNode = findPropertyListNode(skuNode);
                if (propertyListNode != null) {
                    StringBuilder propertyTextBuilder = new StringBuilder();
                    StringBuilder valueTextBuilder = new StringBuilder();
                    for (JsonNode propNode : propertyListNode) {
                        if (!propNode.isObject()) {
                            continue;
                        }
                        String propText = textOf(propNode, PROPERTY_TEXT_FIELDS);
                        String valText = textOf(propNode, VALUE_TEXT_FIELDS);
                        if (propText.isEmpty() && valText.isEmpty()) {
                            continue;
                        }
                        if (propertyTextBuilder.length() > 0) {
                            propertyTextBuilder.append(" ");
                            valueTextBuilder.append(" ");
                        }
                        propertyTextBuilder.append(propText).append(":").append(valText);
                        valueTextBuilder.append(valText);
                    }
                    // 列宽有限，超长规格文案会触发写入失败并回滚整次同步。
                    sku.setPropertyText(truncate(propertyTextBuilder.toString(), 500));
                    sku.setValueText(truncate(valueTextBuilder.toString(), 200));

                    JsonNode firstProp = propertyListNode.get(0);
                    int propertyId = intOf(firstProp, -1, PROPERTY_ID_FIELDS.toArray(String[]::new));
                    if (propertyId >= 0) {
                        sku.setPropertyId(propertyId);
                    }
                    int valueId = intOf(firstProp, -1, VALUE_ID_FIELDS.toArray(String[]::new));
                    if (valueId >= 0) {
                        sku.setValueId(valueId);
                    }
                    int propertySortOrder = intOf(firstProp, -1, "propertySortOrder", "propSortOrder");
                    if (propertySortOrder >= 0) {
                        sku.setPropertySortOrder(propertySortOrder);
                    }
                    int valueSortOrder = intOf(firstProp, -1, "valueSortOrder", "valSortOrder");
                    if (valueSortOrder >= 0) {
                        sku.setValueSortOrder(valueSortOrder);
                    }
                }

                if (skuNode.has("features") && !skuNode.get("features").isNull()) {
                    sku.setFeatures(skuNode.get("features").toString());
                }

                // 平台偶发返回重复SKU编号，重复插入会触发唯一键冲突导致整次同步回滚。
                if (deduplicated.containsKey(sku.getSkuId())) {
                    log.warn("SKU编号重复，已保留最后一次出现: skuId={}", sku.getSkuId());
                }
                deduplicated.put(sku.getSkuId(), sku);
            }

            result.addAll(deduplicated.values());
            if (result.isEmpty()) {
                log.warn("识别到SKU数组但未解析出有效SKU: firstFields={}",
                        skuListNode.isEmpty() ? "[]" : fieldNames(skuListNode.get(0)));
            } else {
                log.info("解析SKU列表成功，数量: {}", result.size());
            }
            return result;
        } catch (Exception e) {
            log.error("解析商品SKU失败: {}", e.getMessage());
            return result;
        }
    }

    public static List<XianyuGoodsSkuProperty> extractSkuPropertyList(String detailJson) {
        List<XianyuGoodsSkuProperty> result = new ArrayList<>();
        if (detailJson == null || detailJson.isEmpty()) {
            return result;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(detailJson);

            JsonNode itemDONode = findItemNode(rootNode);

            if (itemDONode == null || itemDONode.isNull()) {
                return result;
            }

            JsonNode skuListNode = findSkuListNode(itemDONode);

            if (skuListNode == null || !skuListNode.isArray() || skuListNode.size() == 0) {
                return result;
            }

            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode skuNode : skuListNode) {
                JsonNode propertyListNode = findPropertyListNode(skuNode);
                if (propertyListNode == null) {
                    continue;
                }
                for (JsonNode propNode : propertyListNode) {
                    if (!propNode.isObject()) {
                        continue;
                    }
                    int propertyId = intOf(propNode, -1, PROPERTY_ID_FIELDS.toArray(String[]::new));
                    int valueId = intOf(propNode, -1, VALUE_ID_FIELDS.toArray(String[]::new));
                    if (propertyId < 0 || valueId < 0) {
                        continue;
                    }
                    String key = propertyId + ":" + valueId;
                    if (!seen.add(key)) {
                        continue;
                    }

                    XianyuGoodsSkuProperty prop = new XianyuGoodsSkuProperty();
                    prop.setPropertyId(propertyId);
                    prop.setPropertyText(truncate(textOf(propNode, PROPERTY_TEXT_FIELDS), 200));
                    prop.setPropertySortOrder(intOf(propNode, 0, "propertySortOrder", "propSortOrder"));
                    prop.setValueId(valueId);
                    prop.setValueText(truncate(textOf(propNode, VALUE_TEXT_FIELDS), 200));
                    prop.setValueSortOrder(intOf(propNode, 0, "valueSortOrder", "valSortOrder"));
                    result.add(prop);
                }
            }

            log.info("解析SKU属性维度成功，数量: {}", result.size());
            return result;
        } catch (Exception e) {
            log.error("解析SKU属性维度失败: {}", e.getMessage());
            return result;
        }
    }

    public static JsonNode findSkuListNode(JsonNode itemDONode) {
        if (itemDONode == null || itemDONode.isNull()) {
            return null;
        }
        JsonNode emptySkuListNode = null;
        for (String field : SKU_LIST_FIELDS) {
            JsonNode candidate = itemDONode.get(field);
            if (candidate == null || !candidate.isArray()) {
                continue;
            }
            if (!candidate.isEmpty()) {
                return candidate;
            }
            if (emptySkuListNode == null) {
                emptySkuListNode = candidate;
            }
        }
        // 平台响应层级可能调整，按SKU元素特征定位数组，避免依赖固定包装字段。
        JsonNode nestedSkuListNode = findSkuArray(itemDONode, 0);
        return nestedSkuListNode != null ? nestedSkuListNode : emptySkuListNode;
    }

    private static JsonNode findPropertyListNode(JsonNode skuNode) {
        if (skuNode == null) {
            return null;
        }
        for (String field : SKU_PROPERTY_FIELDS) {
            JsonNode node = skuNode.get(field);
            if (node != null && node.isArray() && !node.isEmpty()) {
                return node;
            }
        }
        return null;
    }

    private static String textOf(JsonNode node, List<String> fields) {
        return textOf(node, fields.toArray(String[]::new));
    }

    private static String textOf(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText("").trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static int intOf(JsonNode node, int defaultValue, String... fields) {
        if (node == null) {
            return defaultValue;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            String text = value.asText("").trim();
            if (text.matches("-?\\d{1,10}")) {
                return Integer.parseInt(text);
            }
        }
        return defaultValue;
    }

    private static Integer priceOf(JsonNode priceNode) {
        if (priceNode.isNumber()) {
            return priceNode.asInt();
        }
        String text = priceNode.asText("").trim();
        if (text.matches("\\d{1,10}")) {
            return Integer.parseInt(text);
        }
        // 少数入口返回带小数点的价格文本，按分四舍五入，避免写入0元。
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        String joined = String.join(",", names);
        return joined.length() > 300 ? joined.substring(0, 300) + "..." : joined;
    }

    private static JsonNode findItemNode(JsonNode rootNode) {
        JsonNode itemDONode = rootNode.get("itemDO");
        if (itemDONode == null || itemDONode.isNull()) {
            JsonNode dataNode = rootNode.get("data");
            if (dataNode != null && !dataNode.isNull()) {
                itemDONode = dataNode.get("itemDO");
            }
        }
        return itemDONode;
    }

    private static JsonNode findSkuArray(JsonNode node, int depth) {
        if (node == null || depth > 8) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (isSkuObject(child)) {
                    return node;
                }
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode result = findSkuArray(child, depth + 1);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static boolean isSkuObject(JsonNode child) {
        if (child == null || !child.isObject()) {
            return false;
        }
        if (!textOf(child, SKU_ID_FIELDS).isEmpty()) {
            return true;
        }
        // 只按“规格+价格”双特征识别，避免把商品级规格定义数组误判成SKU数组。
        return findPropertyListNode(child) != null
                && (child.hasNonNull("priceInCent") || child.hasNonNull("price"));
    }
}
