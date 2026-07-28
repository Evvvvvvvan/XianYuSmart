package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.ChangePasswordReqDTO;
import com.xianyusmart.controller.dto.CurrentUserRespDTO;
import com.xianyusmart.controller.dto.VersionInfoRespDTO;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AuthService;
import com.xianyusmart.service.SystemUpdateService;
import com.xianyusmart.service.bo.ChangePasswordReqBO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;


/**
 * 系统设置控制器
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Value("${app.version:2.0.1}")
    private String currentVersion;

    @Autowired
    private AuthService authService;

    @Autowired
    private SystemUpdateService systemUpdateService;

    /**
     * 获取当前用户信息
     */
    @PostMapping("/currentUser")
    public ResultObject<CurrentUserRespDTO> getCurrentUser(HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("currentUserId");
            if (userId == null) {
                return ResultObject.unauthorized(null);
            }
            SysUser user = authService.getCurrentUser(userId);
            if (user == null) {
                return ResultObject.failed("用户不存在");
            }
            CurrentUserRespDTO respDTO = new CurrentUserRespDTO();
            respDTO.setUsername(user.getUsername());
            respDTO.setLastLoginTime(user.getLastLoginTime());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取当前用户信息失败", e);
            return ResultObject.failed("获取当前用户信息失败");
        }
    }

    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    public ResultObject<?> changePassword(@RequestBody ChangePasswordReqDTO reqDTO, HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("currentUserId");
            if (userId == null) {
                return ResultObject.unauthorized(null);
            }
            // 参数校验
            if (reqDTO.getOldPassword() == null || reqDTO.getOldPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("原密码不能为空");
            }
            if (reqDTO.getNewPassword() == null || reqDTO.getNewPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("新密码不能为空");
            }
            if (reqDTO.getNewPassword().length() < 8 || reqDTO.getNewPassword().length() > 72) {
                return ResultObject.validateFailed("新密码长度需在8-72之间");
            }
            if (!reqDTO.getNewPassword().equals(reqDTO.getConfirmPassword())) {
                return ResultObject.validateFailed("两次密码不一致");
            }

            ChangePasswordReqBO reqBO = new ChangePasswordReqBO();
            reqBO.setUserId(userId);
            reqBO.setOldPassword(reqDTO.getOldPassword());
            reqBO.setNewPassword(reqDTO.getNewPassword());
            authService.changePassword(reqBO);

            return ResultObject.success(null);
        } catch (BusinessException e) {
            return ResultObject.failed(e.getMessage());
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResultObject.failed("修改密码失败");
        }
    }

    @GetMapping("/version")
    public ResultObject<String> getVersion() {
        return ResultObject.success(currentVersion);
    }

    @GetMapping("/checkUpdate")
    public ResultObject<VersionInfoRespDTO> checkUpdate() {
        try {
            return ResultObject.success(systemUpdateService.checkUpdate());
        } catch (Exception e) {
            log.error("检查更新失败", e);
            VersionInfoRespDTO respDTO = new VersionInfoRespDTO();
            respDTO.setCurrentVersion(currentVersion);
            respDTO.setLatestVersion(currentVersion);
            respDTO.setHasUpdate(false);
            return ResultObject.success(respDTO);
        }
    }

    @PostMapping("/update")
    public ResultObject<java.util.Map<String, Object>> update() {
        try {
            return ResultObject.success(systemUpdateService.requestUpdate());
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }
}
