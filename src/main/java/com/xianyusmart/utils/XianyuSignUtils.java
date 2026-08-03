package com.xianyusmart.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 闲鱼签名工具类
 */
@Slf4j
public class XianyuSignUtils {

    private static final String APP_KEY = "34839810";
    private static final String HAVANA_LOGIN_COOKIE_PREFIX = "havana_lgc2_";

    /**
     * 将Cookie字符串转换为Map
     */
    public static Map<String, String> parseCookies(String cookiesStr) {
        Map<String, String> cookies = new HashMap<>();
        if (cookiesStr == null || cookiesStr.isEmpty()) {
            return cookies;
        }

        String[] cookieArray = cookiesStr.split(";\\s*");
        for (String cookie : cookieArray) {
            if (cookie.contains("=")) {
                String[] parts = cookie.split("=", 2);
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        }
        return cookies;
    }

    /**
     * 从Cookie中提取账号标识
     */
    public static String extractUserId(String cookieText) {
        Map<String, String> cookies = parseCookies(cookieText);
        String unb = cookies.get("unb");
        if (isNumericUserId(unb)) {
            return unb;
        }

        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (!entry.getKey().startsWith(HAVANA_LOGIN_COOKIE_PREFIX)) {
                continue;
            }
            String hid = extractHavanaUserId(entry.getValue());
            if (hid != null) {
                return hid;
            }
        }
        return null;
    }

    /**
     * 补齐unb以兼容现有平台调用链
     */
    public static String normalizeCookieUserId(String cookieText, String userId) {
        if (cookieText == null || cookieText.isBlank() || !isNumericUserId(userId)
                || parseCookies(cookieText).containsKey("unb")) {
            return cookieText;
        }
        String normalized = cookieText.trim();
        return normalized + (normalized.endsWith(";") ? " " : "; ") + "unb=" + userId;
    }

    private static String extractHavanaUserId(String encodedValue) {
        if (encodedValue == null || encodedValue.isBlank()) {
            return null;
        }
        try {
            String value = encodedValue.trim();
            String paddedValue = value + "=".repeat((4 - value.length() % 4) % 4);
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(paddedValue);
            } catch (IllegalArgumentException e) {
                decoded = Base64.getUrlDecoder().decode(paddedValue);
            }
            JsonElement hid = JsonParser.parseString(
                    new String(decoded, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .get("hid");
            String userId = hid == null || hid.isJsonNull() ? null : hid.getAsString();
            return isNumericUserId(userId) ? userId : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isNumericUserId(String value) {
        return value != null && !value.isBlank()
                && value.chars().allMatch(Character::isDigit);
    }

    /**
     * 生成签名
     *
     * @param timestamp 时间戳
     * @param token     token (_m_h5_tk的前半部分)
     * @param data      数据JSON字符串
     * @return 签名
     */
    public static String generateSign(String timestamp, String token, String data) {
        try {
            String msg = token + "&" + timestamp + "&" + APP_KEY + "&" + data;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(msg.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("生成签名失败", e);
            return "";
        }
    }

    /**
     * 从Cookie中提取token
     */
    public static String extractToken(Map<String, String> cookies) {
        String mH5Tk = cookies.get("_m_h5_tk");
        if (mH5Tk != null && mH5Tk.contains("_")) {
            return mH5Tk.split("_")[0];
        }
        return "";
    }

    /**
     * 将Cookie Map转换为字符串格式
     */
    public static String formatCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
