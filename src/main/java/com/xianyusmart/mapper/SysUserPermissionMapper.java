package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.SysUserPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台用户权限 Mapper
 */
@Mapper
public interface SysUserPermissionMapper extends BaseMapper<SysUserPermission> {

    @Select("SELECT permission_code FROM sys_user_permission WHERE user_id = #{userId} ORDER BY permission_code")
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_permission WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
