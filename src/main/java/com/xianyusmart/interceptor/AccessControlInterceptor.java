package com.xianyusmart.interceptor;

import com.google.gson.Gson;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 菜单与功能权限拦截器
 */
@Component
public class AccessControlInterceptor implements HandlerInterceptor {

    private static final Set<String> ITEM_READ_PATHS = Set.of(
            "/api/items/list", "/api/items/detail", "/api/items/autodeliveryrecords",
            "/api/items/autoreplyrecords", "/api/items/getragautoreplyconfig",
            "/api/items/updateragautoreplyconfig");
    private static final Set<String> KAMI_READ_PATHS = Set.of(
            "/api/kami-config/list", "/api/kami-config/detail", "/api/kami-config/item/list",
            "/api/kami-config/item/query", "/api/kami-config/item/export");
    private static final Set<String> ORDER_READ_PATHS = Set.of(
            "/api/order/list", "/api/order/detail", "/api/order/ratedetails");
    private static final Set<String> AI_READ_PATHS = Set.of(
            "/ai/status", "/ai/queryragdata", "/ai/getfixedmaterial", "/ai/chat", "/ai/chattest");

    private final PlatformPermissionService permissionService;
    private final Gson gson = new Gson();

    public AccessControlInterceptor(PlatformPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        SysUser currentUser = (SysUser) request.getAttribute("currentUser");
        String uri = request.getRequestURI().toLowerCase();
        boolean isAdmin = currentUser != null
                && Integer.valueOf(1).equals(currentUser.getStatus())
                && SysUser.ROLE_ADMIN.equalsIgnoreCase(currentUser.getRole());
        if (uri.startsWith("/api/admin")) {
            if (!isAdmin) {
                writeForbidden(response, "仅平台管理员可以管理账号与权限");
                return false;
            }
            return true;
        }
        if (isAdmin) {
            return true;
        }
        Set<String> permissions = permissionService.getPermissionCodeSet(currentUser);
        String menuPermission = resolveMenuPermission(uri);
        boolean hasMenuPermission = "/api/order/ratedetails".equals(uri)
                ? permissions.contains(PermissionCatalog.MENU_BUYERS)
                    || permissions.contains(PermissionCatalog.MENU_ORDERS)
                : hasPermission(permissions, menuPermission);
        if (!hasMenuPermission) {
            writeForbidden(response, "当前账号没有此菜单的访问权限");
            return false;
        }
        String actionPermission = resolveActionPermission(request.getMethod(), uri);
        if (!hasPermission(permissions, actionPermission)) {
            writeForbidden(response, "当前账号没有执行此操作的功能权限");
            return false;
        }
        return true;
    }

    private boolean hasPermission(Set<String> permissions, String permissionCode) {
        return permissionCode == null || permissionCode.isBlank() || permissions.contains(permissionCode);
    }

    private String resolveMenuPermission(String uri) {
        // 账号选择器、商品基础资料和连接状态被多个页面复用，由各业务页面权限决定是否可见。
        if (uri.equals("/api/account/list") || uri.equals("/api/websocket/status")
                || uri.equals("/api/items/list") || uri.equals("/api/items/detail")
                || uri.startsWith("/api/goods-sku")) {
            return null;
        }
        if (uri.equals("/api/websocket/sendmessage") || uri.equals("/api/websocket/sendimagemessage")) {
            return PermissionCatalog.MENU_MESSAGES;
        }
        if (uri.startsWith("/api/dashboard") || uri.startsWith("/api/data-panel")) {
            return PermissionCatalog.MENU_DASHBOARD;
        }
        if (uri.startsWith("/api/items/autodeliveryrecords") || uri.startsWith("/api/order")) {
            return PermissionCatalog.MENU_ORDERS;
        }
        if (uri.startsWith("/api/items/autoreplyrecords")
                || uri.startsWith("/api/items/getragautoreplyconfig")
                || uri.startsWith("/api/items/updateragautoreplyconfig")
                || uri.startsWith("/api/keyword-reply") || uri.startsWith("/ai")) {
            return PermissionCatalog.MENU_AUTO_REPLY;
        }
        if (uri.startsWith("/api/account") || uri.startsWith("/api/qrlogin")) {
            return PermissionCatalog.MENU_ACCOUNTS;
        }
        if (uri.startsWith("/api/websocket")) {
            return PermissionCatalog.MENU_CONNECTION;
        }
        if (uri.startsWith("/api/items") || uri.startsWith("/api/goods-sku") || uri.startsWith("/api/image")) {
            return PermissionCatalog.MENU_GOODS;
        }
        if (uri.startsWith("/api/merchant")) {
            return PermissionCatalog.MENU_OPERATIONS;
        }
        if (uri.startsWith("/api/msg")) {
            return PermissionCatalog.MENU_MESSAGES;
        }
        if (uri.startsWith("/api/buyers")) {
            return PermissionCatalog.MENU_BUYERS;
        }
        if (uri.startsWith("/api/kami-config")) {
            return PermissionCatalog.MENU_KAMI;
        }
        if (uri.startsWith("/api/fixed-delivery-template")) {
            return PermissionCatalog.MENU_FIXED_DELIVERY;
        }
        if (uri.startsWith("/api/auto-delivery-config") || uri.startsWith("/api/autodelivery")) {
            return PermissionCatalog.MENU_AUTO_DELIVERY;
        }
        if (uri.startsWith("/api/operation-log")) {
            return PermissionCatalog.MENU_OPERATION_LOG;
        }
        if (uri.startsWith("/api/diagnostics") || uri.startsWith("/api/notifications")) {
            return PermissionCatalog.MENU_HEALTH;
        }
        if (uri.startsWith("/api/setting") || uri.startsWith("/api/backup")) {
            return PermissionCatalog.MENU_SETTINGS;
        }
        return null;
    }

    private String resolveActionPermission(String method, String uri) {
        if (uri.startsWith("/api/account")
                && !uri.equals("/api/account/list") && !uri.equals("/api/account/detail")) {
            return PermissionCatalog.ACTION_ACCOUNT_WRITE;
        }
        if (uri.startsWith("/api/qrlogin")) {
            return PermissionCatalog.ACTION_ACCOUNT_WRITE;
        }
        if (uri.equals("/api/websocket/sendmessage") || uri.equals("/api/websocket/sendimagemessage")) {
            return PermissionCatalog.ACTION_MESSAGE_SEND;
        }
        if (uri.startsWith("/api/websocket")
                && !uri.equals("/api/websocket/status") && !uri.equals("/api/websocket/checklogin")) {
            return PermissionCatalog.ACTION_CONNECTION_WRITE;
        }
        if ((uri.startsWith("/api/items") && !ITEM_READ_PATHS.contains(uri)
                && !uri.startsWith("/api/items/syncprogress/") && !uri.startsWith("/api/items/syncing/"))
                || uri.startsWith("/api/image")) {
            return PermissionCatalog.ACTION_GOODS_WRITE;
        }
        if (uri.startsWith("/api/merchant") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_OPERATIONS_WRITE;
        }
        if (uri.equals("/api/buyers/save")) {
            return PermissionCatalog.ACTION_BUYER_WRITE;
        }
        if ((uri.startsWith("/api/kami-config") && !KAMI_READ_PATHS.contains(uri))
                || (uri.startsWith("/api/fixed-delivery-template") && !uri.endsWith("/list"))
                || (uri.startsWith("/api/auto-delivery-config")
                    && !uri.endsWith("/get") && !uri.endsWith("/list") && !uri.endsWith("/listbygoods"))
                || uri.startsWith("/api/autodelivery")) {
            return PermissionCatalog.ACTION_DELIVERY_WRITE;
        }
        if (uri.startsWith("/api/order") && !ORDER_READ_PATHS.contains(uri)) {
            return PermissionCatalog.ACTION_ORDER_WRITE;
        }
        if ((uri.startsWith("/api/keyword-reply") && !uri.endsWith("/rules"))
                || (uri.startsWith("/ai") && !AI_READ_PATHS.contains(uri))
                || uri.equals("/api/items/updateragautoreplyconfig")) {
            return PermissionCatalog.ACTION_AUTOMATION_WRITE;
        }
        if ((uri.startsWith("/api/operation-log") && !uri.endsWith("/query"))
                || (uri.startsWith("/api/diagnostics") && !"GET".equalsIgnoreCase(method))
                || (uri.startsWith("/api/notifications") && !"GET".equalsIgnoreCase(method))
                || (uri.startsWith("/api/setting") && !uri.endsWith("/get") && !uri.endsWith("/list"))
                || uri.equals("/api/backup/import")) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        return null;
    }

    private void writeForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ResultObject<?> result = ResultObject.forbidden(null);
        result.setMsg(message);
        response.getWriter().write(gson.toJson(result));
    }
}
