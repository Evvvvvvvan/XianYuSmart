package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.PlatformUserPasswordReqDTO;
import com.xianyusmart.controller.dto.PlatformUserRespDTO;
import com.xianyusmart.controller.dto.PlatformUserSaveReqDTO;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformUserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理员账号与权限接口
 */
@RestController
@RequestMapping("/api/admin/users")
public class PlatformUserController {

    private final PlatformUserService userService;

    public PlatformUserController(PlatformUserService userService) {
        this.userService = userService;
    }

    @PostMapping("/list")
    public ResultObject<Map<String, Object>> list() {
        return ResultObject.success(userService.list());
    }

    @PostMapping("/permissions")
    public ResultObject<List<PermissionCatalog.PermissionOption>> permissions() {
        return ResultObject.success(userService.permissionOptions());
    }

    @PostMapping("/save")
    public ResultObject<PlatformUserRespDTO> save(@RequestBody PlatformUserSaveReqDTO request) {
        try {
            return ResultObject.success(userService.save(request));
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        }
    }

    @PostMapping("/resetPassword")
    public ResultObject<Void> resetPassword(@RequestBody PlatformUserPasswordReqDTO request) {
        try {
            userService.resetPassword(request);
            return ResultObject.success(null);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getCode(), e.getMessage());
        }
    }
}
