package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 平台账号展示内容
 */
@Data
public class PlatformUserRespDTO {

    private Long id;

    private String username;

    private String role;

    private Integer status;

    private List<String> permissions;

    private String lastLoginTime;

    private String lastLoginIp;

    private String createdTime;
}
