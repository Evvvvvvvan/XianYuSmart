package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 当前用户信息响应DTO
 * @date 2026/4/22
 */
@Data
public class CurrentUserRespDTO {
    private String username;
    private String role;
    private List<String> permissions;
    private String menuLayout;
    private String lastLoginTime;
}
