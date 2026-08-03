package com.xianyusmart.util;

import java.util.Locale;
import java.util.Set;

/** 注册密码规则。 */
public final class RegistrationPasswordPolicy {

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "12345678", "password", "password123", "admin123",
            "qwerty123", "abc12345", "11111111", "00000000"
    );

    private RegistrationPasswordPolicy() {
    }

    public static String validate(String username, String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            return "密码长度需在8-72之间";
        }
        boolean hasLetter = password.chars().anyMatch(RegistrationPasswordPolicy::isLetter);
        boolean hasDigit = password.chars().anyMatch(RegistrationPasswordPolicy::isDigit);
        boolean hasSymbol = password.chars().anyMatch(value -> !isLetter(value) && !isDigit(value));
        int categories = (hasLetter ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);
        if (categories < 2) {
            return "密码至少包含字母、数字、符号中的两类";
        }
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUsername.isEmpty() && normalizedPassword.equals(normalizedUsername)) {
            return "密码不能与账号相同";
        }
        if (COMMON_PASSWORDS.contains(normalizedPassword) || password.chars().distinct().count() == 1) {
            return "密码过于简单，请更换后重试";
        }
        return null;
    }

    private static boolean isLetter(int value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static boolean isDigit(int value) {
        return value >= '0' && value <= '9';
    }
}
