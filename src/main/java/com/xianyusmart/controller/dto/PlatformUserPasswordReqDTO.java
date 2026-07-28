package com.xianyusmart.controller.dto;

import lombok.Data;

/**
 * 管理员重置平台账号密码
 */
@Data
public class PlatformUserPasswordReqDTO {

    private Long userId;

    private String newPassword;
}
