package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统用户Mapper
 * @date 2026/4/22
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT id FROM sys_user WHERE role = 'ADMIN' AND status = 1 FOR UPDATE")
    List<Long> lockActiveAdminIds();
}
