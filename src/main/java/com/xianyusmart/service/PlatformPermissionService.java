package com.xianyusmart.service;

import com.xianyusmart.entity.SysUser;
import com.xianyusmart.entity.SysUserPermission;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.mapper.SysUserPermissionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台权限判定服务
 */
@Service
public class PlatformPermissionService {

    private final SysUserMapper userMapper;
    private final SysUserPermissionMapper permissionMapper;

    public PlatformPermissionService(SysUserMapper userMapper, SysUserPermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
    }

    public boolean isAdmin(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        return user != null
                && Integer.valueOf(1).equals(user.getStatus())
                && SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole());
    }

    public boolean hasPermission(Long userId, String permissionCode) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        return hasPermission(user, permissionCode);
    }

    public boolean hasPermission(SysUser user, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return true;
        }
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return false;
        }
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            return true;
        }
        return getPermissionCodeSet(user).contains(permissionCode);
    }

    public List<String> getPermissionCodes(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return List.of();
        }
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            return PermissionCatalog.options().stream().map(PermissionCatalog.PermissionOption::code).toList();
        }
        return permissionMapper.selectCodesByUserId(userId);
    }

    public Set<String> getPermissionCodeSet(SysUser user) {
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return Set.of();
        }
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            return PermissionCatalog.codes();
        }
        // 权限按请求实时读取，避免事务提交前缓存旧权限导致撤权后仍可继续操作。
        return Set.copyOf(permissionMapper.selectCodesByUserId(user.getId()));
    }

    @Transactional
    public void replacePermissions(Long userId, Collection<String> permissionCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (permissionCodes != null) {
            permissionCodes.stream()
                    .filter(PermissionCatalog.codes()::contains)
                    .forEach(normalized::add);
        }
        permissionMapper.deleteByUserId(userId);
        for (String code : normalized) {
            SysUserPermission permission = new SysUserPermission();
            permission.setUserId(userId);
            permission.setPermissionCode(code);
            permissionMapper.insert(permission);
        }
    }

    public void assignDefaultPermissions(Long userId) {
        replacePermissions(userId, PermissionCatalog.defaultCodes());
    }
}
