package com.xianyusmart.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 创建或修改平台账号
 */
@Data
public class PlatformUserSaveReqDTO {

    private Long id;

    private String username;

    private String password;

    private String role;

    private Integer status;

    private List<String> permissions;
}
