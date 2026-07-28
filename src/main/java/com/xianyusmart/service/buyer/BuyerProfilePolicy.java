package com.xianyusmart.service.buyer;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 买家资料输入规则
 */
public final class BuyerProfilePolicy {

    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 20;

    private BuyerProfilePolicy() {
    }

    public static List<String> normalizeTags(List<String> tags) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                String value = tag == null ? "" : tag.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.length() > MAX_TAG_LENGTH) {
                    throw new IllegalArgumentException("单个买家标签不能超过20个字符");
                }
                normalized.add(value);
                if (normalized.size() > MAX_TAGS) {
                    throw new IllegalArgumentException("每位买家最多设置10个标签");
                }
            }
        }
        return List.copyOf(normalized);
    }

    public static String blockReason(boolean automationBlocked, String reason) {
        if (!automationBlocked) {
            return null;
        }
        String normalized = reason == null ? "" : reason.trim();
        return "买家已停止自动化：" + (normalized.isEmpty() ? "需人工处理" : normalized);
    }
}
