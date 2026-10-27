package com.xianyusmart.service;

import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCredentialRecoveryTest {

    @Test
    void resetReplacesHashAndClearsLoginTokens() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysLoginTokenMapper tokenMapper = mock(SysLoginTokenMapper.class);
        SysUser admin = new SysUser();
        admin.setId(7L);
        admin.setUsername("admin");
        admin.setPassword(new BCryptPasswordEncoder().encode("old-password"));
        admin.setRole("USER");
        admin.setStatus(0);
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(userMapper.selectList(any())).thenReturn(List.of(admin));

        AdminCredentialRecovery recovery = new AdminCredentialRecovery(userMapper, tokenMapper);
        ReflectionTestUtils.setField(recovery, "resetUsername", " admin ");
        ReflectionTestUtils.setField(recovery, "resetPassword", "new-strong-password");

        recovery.run(null);

        assertTrue(new BCryptPasswordEncoder().matches("new-strong-password", admin.getPassword()));
        assertEquals(SysUser.ROLE_ADMIN, admin.getRole());
        assertEquals(1, admin.getStatus());
        assertNotNull(admin.getUpdatedTime());
        verify(userMapper).updateById(admin);
        verify(tokenMapper).delete(any());
    }

    @Test
    void resetIsSkippedWhenOnlyUsernameConfigured() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysLoginTokenMapper tokenMapper = mock(SysLoginTokenMapper.class);
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdminCredentialRecovery recovery = new AdminCredentialRecovery(userMapper, tokenMapper);
        ReflectionTestUtils.setField(recovery, "resetUsername", "admin");
        ReflectionTestUtils.setField(recovery, "resetPassword", "");

        recovery.run(null);

        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(tokenMapper, never()).delete(any());
    }

    @Test
    void resetIsSkippedWhenUsernameDoesNotExist() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysLoginTokenMapper tokenMapper = mock(SysLoginTokenMapper.class);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdminCredentialRecovery recovery = new AdminCredentialRecovery(userMapper, tokenMapper);
        ReflectionTestUtils.setField(recovery, "resetUsername", "admin");
        ReflectionTestUtils.setField(recovery, "resetPassword", "new-strong-password");

        recovery.run(null);

        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(tokenMapper, never()).delete(any());
    }

    @Test
    void passwordPolicyRejectsShortPlaceholderAndOverlongValues() {
        assertNotNull(AdminCredentialRecovery.validatePassword("admin", "short"));
        assertNotNull(AdminCredentialRecovery.validatePassword("admin", "change-me-bootstrap-admin-password"));
        assertNotNull(AdminCredentialRecovery.validatePassword("admin", "a".repeat(73)));
        assertNotNull(AdminCredentialRecovery.validatePassword("admin", "onlyletters"));
        assertNull(AdminCredentialRecovery.validatePassword("admin", "good-password-2026"));
    }

    @Test
    void bcryptFormatIsDetectedWithoutExposingSecret() {
        String hash = new BCryptPasswordEncoder().encode("secret-password");
        assertTrue(AdminCredentialRecovery.isBcryptHash(hash));
        assertFalse(AdminCredentialRecovery.isBcryptHash("123456"));
        assertFalse(AdminCredentialRecovery.isBcryptHash(null));

        String description = AdminCredentialRecovery.describePasswordHash(hash);
        assertTrue(description.contains("格式=BCrypt"));
        assertTrue(description.contains("长度=60"));
        assertFalse(description.contains(hash));
        assertTrue(AdminCredentialRecovery.describePasswordHash("  abc  ").contains("含首尾空白"));
    }
}
