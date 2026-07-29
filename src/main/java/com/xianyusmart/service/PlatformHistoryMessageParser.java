package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuChatMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台历史会话消息解析
 */
public class PlatformHistoryMessageParser {

    private final ObjectMapper objectMapper;

    public PlatformHistoryMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<XianyuChatMessage> parse(Long accountId, String sid, List<Map<String, Object>> models) {
        List<XianyuChatMessage> result = new ArrayList<>();
        for (Map<String, Object> model : models) {
            Map<String, Object> message = firstNonEmptyMap(model.get("message"), model);
            Map<String, Object> extension = map(message.get("extension"));
            Map<String, Object> content = map(message.get("content"));
            Map<String, Object> custom = map(content.get("custom"));
            Map<String, Object> decodedContent = decodeContent(text(custom.get("data")));

            Long messageTime = firstLong(message, model,
                    "createdAt", "createTime", "sendTime", "messageTime", "timestamp");
            String senderUserId = firstNonBlank(
                    firstText(extension, "senderUserId"),
                    firstText(message, "senderUserId", "senderId", "fromId"));
            String pnmId = firstNonBlank(
                    firstText(message, "messageId", "id", "mid", "uuid"),
                    firstText(model, "messageId", "id", "mid", "uuid"));
            if (pnmId.isBlank()) {
                // 历史协议部分消息不返回消息ID，使用稳定业务字段生成幂等键，避免同步时遗漏或重复。
                String source = sid + "|" + senderUserId + "|" + messageTime + "|" + text(custom.get("data"));
                pnmId = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
            }

            Integer contentType = integer(decodedContent.get("contentType"));
            String messageContent = parseContent(decodedContent, extension);
            XianyuChatMessage chatMessage = new XianyuChatMessage();
            chatMessage.setXianyuAccountId(accountId);
            chatMessage.setLwp("/r/MessageManager/listUserMessages");
            chatMessage.setPnmId(pnmId);
            chatMessage.setSId(firstNonBlank(firstText(message, "cid", "sid"), sid));
            chatMessage.setContentType(contentType);
            chatMessage.setMsgContent(messageContent);
            chatMessage.setSenderUserName(firstNonBlank(
                    firstText(extension, "reminderTitle", "senderUserName"),
                    firstText(message, "senderUserName", "senderNick")));
            chatMessage.setSenderUserId(senderUserId);
            chatMessage.setReminderUrl(firstText(extension, "reminderUrl"));
            chatMessage.setXyGoodsId(extractItemId(chatMessage.getReminderUrl()));
            chatMessage.setSenderAppV(firstText(extension, "_appVersion"));
            chatMessage.setSenderOsType(firstText(extension, "_platform"));
            chatMessage.setMessageTime(messageTime);
            chatMessage.setCompleteMsg(writeJson(model));
            result.add(chatMessage);
        }
        return result;
    }

    private String parseContent(Map<String, Object> decodedContent, Map<String, Object> extension) {
        Integer contentType = integer(decodedContent.get("contentType"));
        if (contentType != null && contentType == 1) {
            String text = firstText(map(decodedContent.get("text")), "text");
            if (!text.isBlank()) {
                return text;
            }
        }
        if (contentType != null && contentType == 2) {
            Object picsValue = map(decodedContent.get("image")).get("pics");
            if (picsValue instanceof List<?> pics && !pics.isEmpty()) {
                String imageUrl = firstText(map(pics.get(0)), "url");
                if (!imageUrl.isBlank()) {
                    return https(imageUrl);
                }
            }
        }
        return firstText(extension, "reminderContent", "summary");
    }

    private Map<String, Object> decodeContent(String data) {
        if (data.isBlank()) {
            return Map.of();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            return objectMapper.readValue(new String(decoded, StandardCharsets.UTF_8), new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Long firstLong(Map<String, Object> primary, Map<String, Object> fallback, String... keys) {
        for (String key : keys) {
            Long value = longValue(primary.get(key));
            if (value != null) {
                return normalizeTime(value);
            }
            value = longValue(fallback.get(key));
            if (value != null) {
                return normalizeTime(value);
            }
        }
        return System.currentTimeMillis();
    }

    private Long normalizeTime(Long value) {
        return value > 0 && value < 100000000000L ? value * 1000 : value;
    }

    private String extractItemId(String url) {
        if (url == null) {
            return null;
        }
        int index = url.indexOf("itemId=");
        if (index < 0) {
            return null;
        }
        int start = index + 7;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
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

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private Integer integer(Object value) {
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String https(String value) {
        return value.startsWith("//") ? "https:" + value
                : value.startsWith("http://") ? "https://" + value.substring(7) : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
