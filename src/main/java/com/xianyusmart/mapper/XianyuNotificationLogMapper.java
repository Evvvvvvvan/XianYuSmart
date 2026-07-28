package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuNotificationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 通知发送记录 Mapper
 */
@Mapper
public interface XianyuNotificationLogMapper extends BaseMapper<XianyuNotificationLog> {

    @Select("SELECT * FROM xianyu_notification_log ORDER BY create_time DESC LIMIT #{limit}")
    List<XianyuNotificationLog> selectRecent(@Param("limit") int limit);
}
