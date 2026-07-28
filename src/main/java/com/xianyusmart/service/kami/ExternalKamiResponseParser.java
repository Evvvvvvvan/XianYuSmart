package com.xianyusmart.service.kami;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部卡密接口响应解析器
 */
public class ExternalKamiResponseParser {

    private final ObjectMapper objectMapper;

    public ExternalKamiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> parse(String responseBody, String resultPath, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("卡密数量必须大于0");
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            for (String segment : resultPath.split("\\.")) {
                node = node.path(segment);
            }
            List<String> contents = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> addContent(contents, item));
            } else {
                addContent(contents, node);
            }
            if (contents.size() != quantity) {
                throw new IllegalArgumentException("外部接口返回的卡密数量与订单数量不一致");
            }
            return contents;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("外部接口响应不是有效 JSON");
        }
    }

    private void addContent(List<String> contents, JsonNode node) {
        String value = node.isValueNode() ? node.asText().trim() : "";
        if (!value.isEmpty()) {
            contents.add(value);
        }
    }
}
