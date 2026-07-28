package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuNotificationChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 通知渠道 Mapper
 */
@Mapper
public interface XianyuNotificationChannelMapper extends BaseMapper<XianyuNotificationChannel> {

    @Select("SELECT * FROM xianyu_notification_channel ORDER BY update_time DESC, id DESC")
    List<XianyuNotificationChannel> selectAll();

    @Select("SELECT * FROM xianyu_notification_channel WHERE enabled = 1 ORDER BY id")
    List<XianyuNotificationChannel> selectEnabled();

    @Update("UPDATE xianyu_notification_channel SET last_success_time = NOW(3), last_error_message = NULL " +
            "WHERE id = #{id}")
    int markSuccess(@Param("id") Long id);

    @Update("UPDATE xianyu_notification_channel SET last_error_message = #{message} WHERE id = #{id}")
    int markFailure(@Param("id") Long id, @Param("message") String message);
}
