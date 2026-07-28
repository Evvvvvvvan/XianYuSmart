package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.controller.dto.PlatformUserPasswordReqDTO;
import com.xianyusmart.controller.dto.PlatformUserRespDTO;
import com.xianyusmart.controller.dto.PlatformUserSaveReqDTO;
import com.xianyusmart.entity.SysLoginToken;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全站账号与权限管理
 */
@Service
public class PlatformUserService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final SysUserMapper userMapper;
    private final SysLoginTokenMapper loginTokenMapper;
    private final PlatformPermissionService permissionService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public PlatformUserService(SysUserMapper userMapper,
                               SysLoginTokenMapper loginTokenMapper,
                               PlatformPermissionService permissionService) {
        this.userMapper = userMapper;
        this.loginTokenMapper = loginTokenMapper;
        this.permissionService = permissionService;
    }

    public Map<String, Object> list() {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getId));
        List<PlatformUserRespDTO> records = users.stream().map(this::toResponse).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", records.size());
        result.put("activeCount", records.stream().filter(item -> Integer.valueOf(1).equals(item.getStatus())).count());
        result.put("adminCount", records.stream()
                .filter(item -> SysUser.ROLE_ADMIN.equalsIgnoreCase(item.getRole())).count());
        return result;
    }

    @Transactional
    public PlatformUserRespDTO save(PlatformUserSaveReqDTO request) {
        String role = normalizeRole(request.getRole());
        int status = Integer.valueOf(0).equals(request.getStatus()) ? 0 : 1;
        List<String> permissions = normalizePermissions(request.getPermissions(), role);
        SysUser user;
        if (request.getId() == null) {
            user = createUser(request, role, status);
        } else {
            user = updateUser(request.getId(), role, status);
        }
        if (SysUser.ROLE_ADMIN.equals(role)) {
            permissionService.replacePermissions(user.getId(), List.of());
        } else {
            permissionService.replacePermissions(user.getId(), permissions);
        }
        return toResponse(userMapper.selectById(user.getId()));
    }

    @Transactional
    public void resetPassword(PlatformUserPasswordReqDTO request) {
        if (request.getUserId() == null) {
            throw new BusinessException(400, "账号ID不能为空");
        }
        validatePassword(request.getNewPassword());
        SysUser user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BusinessException(404, "平台账号不存在");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedTime(now());
        userMapper.updateById(user);
        revokeTokens(user.getId());
    }

    public List<PermissionCatalog.PermissionOption> permissionOptions() {
        return PermissionCatalog.options();
    }

    private SysUser createUser(PlatformUserSaveReqDTO request, String role, int status) {
        String username = normalizeUsername(request.getUsername());
        validatePassword(request.getPassword());
        Long duplicate = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(400, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setStatus(status);
        user.setCreatedTime(now());
        user.setUpdatedTime(now());
        userMapper.insert(user);
        return user;
    }

    private SysUser updateUser(Long userId, String role, int status) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "平台账号不存在");
        }
        boolean removingActiveAdmin = SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())
                && Integer.valueOf(1).equals(user.getStatus())
                && (!SysUser.ROLE_ADMIN.equals(role) || status == 0);
        if (removingActiveAdmin) {
            // 数据库行锁覆盖事务提交阶段和多实例并发，确保全站始终保留启用管理员。
            List<Long> activeAdminIds = userMapper.lockActiveAdminIds();
            if (activeAdminIds.contains(userId) && activeAdminIds.size() <= 1) {
                throw new BusinessException(400, "至少需要保留一个启用中的管理员账号");
            }
        }
        boolean identityChanged = !role.equalsIgnoreCase(user.getRole()) || status != user.getStatus();
        user.setRole(role);
        user.setStatus(status);
        user.setUpdatedTime(now());
        userMapper.updateById(user);
        if (identityChanged) {
            revokeTokens(userId);
        }
        return user;
    }

    private List<String> normalizePermissions(List<String> permissionCodes, String role) {
        if (SysUser.ROLE_ADMIN.equals(role)) {
            return List.of();
        }
        Set<String> allowed = PermissionCatalog.codes();
        List<String> normalized = permissionCodes == null ? List.of() : permissionCodes.stream()
                .filter(allowed::contains)
                .distinct()
                .toList();
        boolean hasMenu = normalized.stream().anyMatch(code -> code.startsWith("menu:"));
        if (!hasMenu) {
            throw new BusinessException(400, "普通用户至少需要一个菜单权限");
        }
        return normalized;
    }

    private String normalizeRole(String role) {
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(role)) {
            return SysUser.ROLE_ADMIN;
        }
        if (SysUser.ROLE_USER.equalsIgnoreCase(role)) {
            return SysUser.ROLE_USER;
        }
        throw new BusinessException(400, "账号角色无效");
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.length() < 3 || normalized.length() > 20) {
            throw new BusinessException(400, "用户名长度需在3-20之间");
        }
        if (!normalized.matches("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]+")) {
            throw new BusinessException(400, "用户名只能包含中英文、数字、下划线或短横线");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new BusinessException(400, "密码长度需在8-72之间");
        }
    }

    private void revokeTokens(Long userId) {
        loginTokenMapper.delete(new LambdaQueryWrapper<SysLoginToken>()
                .eq(SysLoginToken::getUserId, userId));
    }

    private PlatformUserRespDTO toResponse(SysUser user) {
        PlatformUserRespDTO response = new PlatformUserRespDTO();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setPermissions(permissionService.getPermissionCodes(user.getId()));
        response.setLastLoginTime(user.getLastLoginTime());
        response.setLastLoginIp(user.getLastLoginIp());
        response.setCreatedTime(user.getCreatedTime());
        return response;
    }

    private String now() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
