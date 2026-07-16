package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.MerchantShortLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户短链Mapper
 */
@Mapper
public interface MerchantShortLinkMapper extends BaseMapper<MerchantShortLink> {

    @Select("SELECT * FROM merchant_short_link WHERE token = #{token} LIMIT 1")
    MerchantShortLink selectByToken(@Param("token") String token);

    @Update("UPDATE merchant_short_link SET click_count = click_count + 1 WHERE id = #{id}")
    int incrementClicks(@Param("id") Long id);
}
