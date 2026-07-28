package com.xianyusmart.interceptor;

import com.xianyusmart.annotation.NoAuth;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.util.JwtUtil;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 登录认证拦截器
 * 检查接口是否有@NoAuth注解，有则放行，否则验证Token
 * @date 2026/4/22
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthService authService;

    private final Gson gson = new Gson();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 非Controller方法直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查方法或类上是否有@NoAuth注解
        if (handlerMethod.getMethodAnnotation(NoAuth.class) != null
                || handlerMethod.getBeanType().getAnnotation(NoAuth.class) != null) {
            return true;
        }

        // 从Header中获取Token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 验证Token
        if (token == null || token.isEmpty()) {
            writeUnauthorized(response, "暂未登录或token已经过期");
            return false;
        }

        if (!jwtUtil.validateToken(token)) {
            writeUnauthorized(response, "Token无效或已过期");
            return false;
        }

        // 验证Token是否在数据库中（是否被挤下线）
        if (!authService.isTokenValid(token)) {
            writeUnauthorized(response, "账号已在其他设备登录");
            return false;
        }

        // 将用户信息放入request属性，供Controller使用
        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);
        SysUser currentUser = authService.getCurrentUser(userId);
        if (currentUser == null || !Integer.valueOf(1).equals(currentUser.getStatus())) {
            writeUnauthorized(response, "账号已被禁用");
            return false;
        }
        request.setAttribute("currentUserId", userId);
        request.setAttribute("currentUsername", username);
        
        // 同时设置到UserContext（ThreadLocal），供任意位置获取
        com.xianyusmart.context.UserContext.set(userId, username);

        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后清理UserContext，防止内存泄漏
        com.xianyusmart.context.UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ResultObject<?> result = ResultObject.unauthorized(null);
        result.setMsg(message);
        response.getWriter().write(gson.toJson(result));
    }
}
