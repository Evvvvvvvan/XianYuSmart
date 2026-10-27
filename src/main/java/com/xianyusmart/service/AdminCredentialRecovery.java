package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.entity.SysLoginToken;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.util.RegistrationPasswordPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 管理员凭据自检与应急重置。
 *
 * <p>升级后“数据库里有 admin 但登录仍提示用户名或密码错误”通常是以下三类问题：
 * 账号用户名带空白字符、password 字段不是合法的 BCrypt 哈希、或账号角色/状态被迁移脚本改错。
 * 启动时输出脱敏自检信息，并支持通过环境变量一次性重置管理员密码，避免只能手改数据库。</p>
 */
@Slf4j
@Component
@Order(20)
public class AdminCredentialRecovery implements ApplicationRunner {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String BCRYPT_PATTERN = "\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}";

    private final SysUserMapper sysUserMapper;
    private final SysLoginTokenMapper sysLoginTokenMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.admin.reset-username:}")
    private String resetUsername;

    @Value("${app.admin.reset-password:}")
    private String resetPassword;

    @Autowired
    public AdminCredentialRecovery(SysUserMapper sysUserMapper,
                                   SysLoginTokenMapper sysLoginTokenMapper) {
        this.sysUserMapper = sysUserMapper;
        this.sysLoginTokenMapper = sysLoginTokenMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (hasText(resetUsername) || hasText(resetPassword)) {
            resetAdminPassword();
        }
        logAdminCredentialState();
    }

    private void resetAdminPassword() {
        String username = resetUsername == null ? "" : resetUsername.trim();
        String password = resetPassword == null ? "" : resetPassword;
        if (username.isEmpty() || password.isEmpty()) {
            log.warn("[Auth] 管理员密码重置已跳过：ADMIN_RESET_USERNAME 与 ADMIN_RESET_PASSWORD 需同时配置");
            return;
        }
        if (username.length() < 3 || username.length() > 20) {
            log.warn("[Auth] 管理员密码重置已跳过：用户名长度需在3-20之间");
            return;
        }
        String passwordError = validatePassword(username, password);
        if (passwordError != null) {
            log.warn("[Auth] 管理员密码重置已跳过：{}", passwordError);
            return;
        }
        int sameNameCount = countByUsername(username);
        if (sameNameCount == 0) {
            log.warn("[Auth] 管理员密码重置已跳过：账号 {} 不存在，请确认用户名或改用 BOOTSTRAP_ADMIN_* 初始化",
                    masked(username));
            return;
        }
        if (sameNameCount > 1) {
            log.warn("[Auth] 管理员密码重置已跳过：账号 {} 存在 {} 条重复记录，请先清理 sys_user",
                    masked(username), sameNameCount);
            return;
        }

        SysUser user = findFirstByUsername(username);
        if (user == null || user.getId() == null) {
            log.warn("[Auth] 管理员密码重置已跳过：无法读取账号记录");
            return;
        }
        String now = LocalDateTime.now().format(FORMATTER);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(SysUser.ROLE_ADMIN);
        user.setStatus(1);
        user.setUpdatedTime(now);
        sysUserMapper.updateById(user);
        // 重置密码后清空旧登录态，避免历史Token绕过新密码。
        sysLoginTokenMapper.delete(new LambdaQueryWrapper<SysLoginToken>()
                .eq(SysLoginToken::getUserId, user.getId()));
        log.info("[Auth] 管理员密码已重置：username={}，role=ADMIN，status=1，旧登录态已清除",
                masked(username));
        log.info("[Auth] 请登录成功后从部署配置中移除 ADMIN_RESET_PASSWORD 并重启，避免明文密码留在环境变量里");
    }

    private void logAdminCredentialState() {
        List<SysUser> admins = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, SysUser.ROLE_ADMIN)
                .eq(SysUser::getStatus, 1));
        if (admins.isEmpty()) {
            log.warn("[Auth] 未找到启用的平台管理员账号，请配置 BOOTSTRAP_ADMIN_USERNAME/BOOTSTRAP_ADMIN_PASSWORD"
                    + " 初始化，或用 ADMIN_RESET_USERNAME/ADMIN_RESET_PASSWORD 恢复已有账号");
            return;
        }
        for (SysUser admin : admins) {
            String username = admin.getUsername() == null ? "" : admin.getUsername();
            String hash = admin.getPassword() == null ? "" : admin.getPassword();
            log.info("[Auth] 管理员账号自检：username={}，role={}，status={}，password={}",
                    masked(username), admin.getRole(), admin.getStatus(), describePasswordHash(hash));
            if (!username.equals(username.trim())) {
                log.warn("[Auth] 管理员账号 {} 的用户名包含首尾空白字符，登录时需要完全一致，建议修正 sys_user.username",
                        masked(username));
            }
            if (!isBcryptHash(hash)) {
                log.warn("[Auth] 管理员账号 {} 的 password 不是标准 BCrypt 哈希，登录必然失败；"
                        + "可配置 ADMIN_RESET_USERNAME/ADMIN_RESET_PASSWORD 重启后自动重置",
                        masked(username));
            }
        }
    }

    static String validatePassword(String username, String password) {
        if (password == null || password.isEmpty()) {
            return "密码不能为空";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return "密码不能超过72字节（BCrypt上限）";
        }
        if (password.startsWith("change-me-")) {
            return "密码仍是示例占位值";
        }
        // 与注册流程保持同一套强度要求，避免重置出弱口令管理员账号。
        return RegistrationPasswordPolicy.validate(username, password);
    }

    public static boolean isBcryptHash(String hash) {
        return hash != null && hash.trim().matches(BCRYPT_PATTERN);
    }

    static String describePasswordHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            return "空";
        }
        String trimmed = hash.trim();
        StringBuilder description = new StringBuilder();
        description.append("长度=").append(trimmed.length());
        if (isBcryptHash(trimmed)) {
            description.append("，格式=BCrypt，前缀=").append(trimmed.substring(0, 4));
        } else {
            description.append("，格式=非标准BCrypt");
            if (trimmed.length() > 0) {
                description.append("，前缀=").append(trimmed.substring(0, Math.min(7, trimmed.length())));
            }
        }
        if (!trimmed.equals(hash)) {
            description.append("，含首尾空白");
        }
        return description.toString();
    }

    static String masked(String value) {
        if (value == null || value.isEmpty()) {
            return "[]";
        }
        return "[" + value + "]";
    }

    private int countByUsername(String username) {
        return Math.toIntExact(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)));
    }

    private SysUser findFirstByUsername(String username) {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("limit 2"));
        return users.isEmpty() ? null : users.getFirst();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
