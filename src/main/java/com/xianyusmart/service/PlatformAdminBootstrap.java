package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全新环境平台管理员初始化
 */
@Slf4j
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.admin.bootstrap-username:}")
    private String bootstrapUsername;

    @Value("${app.admin.bootstrap-password:}")
    private String bootstrapPassword;

    public PlatformAdminBootstrap(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long adminCount = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, SysUser.ROLE_ADMIN)
                .eq(SysUser::getStatus, 1));
        if (adminCount > 0) {
            return;
        }
        if (bootstrapUsername == null || bootstrapUsername.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()
                || bootstrapPassword.startsWith("change-me-")) {
            log.warn("[Auth] 当前没有平台管理员，需配置 BOOTSTRAP_ADMIN_USERNAME 和 BOOTSTRAP_ADMIN_PASSWORD");
            return;
        }
        if (bootstrapUsername.length() < 3 || bootstrapUsername.length() > 20
                || bootstrapPassword.length() < 12 || bootstrapPassword.length() > 72) {
            throw new IllegalStateException("平台管理员初始化凭据不符合安全要求");
        }
        long sameUsername = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, bootstrapUsername));
        if (sameUsername > 0) {
            log.warn("[Auth] 平台管理员初始化用户名已被占用，未自动变更现有账号权限");
            return;
        }

        String now = LocalDateTime.now().format(FORMATTER);
        SysUser admin = new SysUser();
        admin.setUsername(bootstrapUsername);
        admin.setPassword(passwordEncoder.encode(bootstrapPassword));
        admin.setRole(SysUser.ROLE_ADMIN);
        admin.setStatus(1);
        admin.setCreatedTime(now);
        admin.setUpdatedTime(now);
        sysUserMapper.insert(admin);
        log.info("[Auth] 平台管理员初始化完成: username={}", bootstrapUsername);
    }
}
