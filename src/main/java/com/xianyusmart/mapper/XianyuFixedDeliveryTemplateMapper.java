package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XianyuFixedDeliveryTemplateMapper extends BaseMapper<XianyuFixedDeliveryTemplate> {

    @Select("SELECT * FROM xianyu_fixed_delivery_template WHERE xianyu_account_id = #{accountId} ORDER BY update_time DESC")
    List<XianyuFixedDeliveryTemplate> findByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT * FROM xianyu_fixed_delivery_template WHERE id = #{id} AND xianyu_account_id = #{accountId} LIMIT 1")
    XianyuFixedDeliveryTemplate findOwnedById(@Param("accountId") Long accountId, @Param("id") Long id);

    @Select("SELECT * FROM xianyu_fixed_delivery_template WHERE xianyu_account_id = #{accountId} AND template_name = #{name} LIMIT 1")
    XianyuFixedDeliveryTemplate findByAccountIdAndName(@Param("accountId") Long accountId,
                                                       @Param("name") String name);

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_delivery_config WHERE fixed_template_id = #{templateId}")
    int countReferencedConfigs(@Param("templateId") Long templateId);
}
