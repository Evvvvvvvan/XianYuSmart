package com.xianyusmart.service;

import java.util.List;
import java.util.Set;

/**
 * 菜单与功能权限目录
 */
public final class PermissionCatalog {

    public static final String MENU_DASHBOARD = "menu:dashboard";
    public static final String MENU_ACCOUNTS = "menu:accounts";
    public static final String MENU_CONNECTION = "menu:connection";
    public static final String MENU_GOODS = "menu:goods";
    public static final String MENU_OPERATIONS = "menu:operations";
    public static final String MENU_MESSAGES = "menu:messages";
    public static final String MENU_BUYERS = "menu:buyers";
    public static final String MENU_KAMI = "menu:kami";
    public static final String MENU_FIXED_DELIVERY = "menu:fixed-delivery";
    public static final String MENU_AUTO_DELIVERY = "menu:auto-delivery";
    public static final String MENU_ORDERS = "menu:orders";
    public static final String MENU_AUTO_REPLY = "menu:auto-reply";
    public static final String MENU_OPERATION_LOG = "menu:operation-log";
    public static final String MENU_HEALTH = "menu:health";
    public static final String MENU_SETTINGS = "menu:settings";

    public static final String ACTION_ACCOUNT_WRITE = "action:account-write";
    public static final String ACTION_CONNECTION_WRITE = "action:connection-write";
    public static final String ACTION_GOODS_WRITE = "action:goods-write";
    public static final String ACTION_OPERATIONS_WRITE = "action:operations-write";
    public static final String ACTION_MESSAGE_SEND = "action:message-send";
    public static final String ACTION_BUYER_WRITE = "action:buyer-write";
    public static final String ACTION_DELIVERY_WRITE = "action:delivery-write";
    public static final String ACTION_ORDER_WRITE = "action:order-write";
    public static final String ACTION_AUTOMATION_WRITE = "action:automation-write";
    public static final String ACTION_SYSTEM_WRITE = "action:system-write";

    private static final List<PermissionOption> OPTIONS = List.of(
            menu(MENU_DASHBOARD, "经营面板", "经营"),
            menu(MENU_ACCOUNTS, "闲鱼账号", "经营"),
            menu(MENU_CONNECTION, "连接管理", "经营"),
            menu(MENU_GOODS, "商品管理", "经营"),
            menu(MENU_OPERATIONS, "运营中心", "经营"),
            menu(MENU_MESSAGES, "消息管理", "客户"),
            menu(MENU_BUYERS, "买家管理", "客户"),
            menu(MENU_KAMI, "卡密仓库", "自动化"),
            menu(MENU_FIXED_DELIVERY, "固定内容模板", "自动化"),
            menu(MENU_AUTO_DELIVERY, "自动发货", "自动化"),
            menu(MENU_ORDERS, "订单与评价", "自动化"),
            menu(MENU_AUTO_REPLY, "自动回复", "自动化"),
            menu(MENU_OPERATION_LOG, "操作日志", "系统"),
            menu(MENU_HEALTH, "通知与诊断", "系统"),
            menu(MENU_SETTINGS, "系统设置", "系统"),
            action(ACTION_ACCOUNT_WRITE, "维护闲鱼账号", "账号"),
            action(ACTION_CONNECTION_WRITE, "维护连接与凭据", "账号"),
            action(ACTION_GOODS_WRITE, "同步及编辑商品", "商品"),
            action(ACTION_OPERATIONS_WRITE, "执行运营任务", "运营"),
            action(ACTION_MESSAGE_SEND, "发送消息", "客户"),
            action(ACTION_BUYER_WRITE, "编辑买家资料", "客户"),
            action(ACTION_DELIVERY_WRITE, "配置及执行发货", "履约"),
            action(ACTION_ORDER_WRITE, "处理订单与评价", "履约"),
            action(ACTION_AUTOMATION_WRITE, "配置回复与知识库", "自动化"),
            action(ACTION_SYSTEM_WRITE, "修改系统与通知设置", "系统")
    );

    private static final Set<String> CODES = OPTIONS.stream()
            .map(PermissionOption::code)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private PermissionCatalog() {
    }

    public static List<PermissionOption> options() {
        return OPTIONS;
    }

    public static Set<String> codes() {
        return CODES;
    }

    public static Set<String> defaultCodes() {
        return CODES;
    }

    private static PermissionOption menu(String code, String label, String group) {
        return new PermissionOption(code, label, group, "MENU");
    }

    private static PermissionOption action(String code, String label, String group) {
        return new PermissionOption(code, label, group, "ACTION");
    }

    public record PermissionOption(String code, String label, String group, String type) {
    }
}
