package com.xianyusmart.service.notification;

/**
 * 通知文案变量渲染
 */
public final class NotificationTemplateRenderer {

    public static final String DEFAULT_TEMPLATE = "【{eventName}】{title}\n{content}\n账号：{accountId}";

    private NotificationTemplateRenderer() {
    }

    public static String render(String template, String eventName, String title,
                                String content, Long accountId) {
        String normalized = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
        return normalized
                .replace("{eventName}", safe(eventName))
                .replace("{title}", safe(title))
                .replace("{content}", safe(content))
                .replace("{accountId}", accountId == null ? "-" : accountId.toString());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
