package com.xianyusmart.service.impl;

import com.xianyusmart.entity.SysLoginToken;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.service.bo.LoginReqBO;
import com.xianyusmart.service.bo.LoginRespBO;
import com.xianyusmart.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    @Test
    void loginAcceptsExistingBcryptHashAfterUpgrade() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysLoginTokenMapper tokenMapper = mock(SysLoginTokenMapper.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);

        SysUser admin = new SysUser();
        admin.setId(1L);
        admin.setUsername("admin");
        String existingHash = new BCryptPasswordEncoder().encode("upgrade-password");
        admin.setPassword(existingHash);
        admin.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(admin);
        when(jwtUtil.generateToken(1L, "admin")).thenReturn("signed-token");
        when(jwtUtil.getExpiration()).thenReturn(28_800_000L);

        AuthServiceImpl service = new AuthServiceImpl();
        ReflectionTestUtils.setField(service, "sysUserMapper", userMapper);
        ReflectionTestUtils.setField(service, "sysLoginTokenMapper", tokenMapper);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);

        LoginReqBO request = new LoginReqBO();
        request.setUsername("admin");
        request.setPassword("upgrade-password");
        request.setIp("127.0.0.1");
        request.setDeviceId("test");

        LoginRespBO response = service.login(request);

        assertEquals("admin", response.getUsername());
        assertEquals("signed-token", response.getToken());
        verify(tokenMapper).insert(any(SysLoginToken.class));
        verify(userMapper).updateById(admin);
    }
}
