package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.MerchantResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家运营资源Mapper
 */
@Mapper
public interface MerchantResourceMapper extends BaseMapper<MerchantResource> {

    @Select("<script>SELECT * FROM merchant_resource WHERE resource_type = #{type} " +
            "<if test='status != null'>AND status = #{status}</if> ORDER BY updated_time DESC</script>")
    List<MerchantResource> selectByType(@Param("type") String type, @Param("status") Integer status);

    @Select("SELECT * FROM merchant_resource WHERE status = 1 AND resource_type IN ('SELECTION_RULE','PUBLISH_RULE','DELETE_RULE') " +
            "AND scheduled_time IS NOT NULL AND scheduled_time <= NOW(3) ORDER BY scheduled_time LIMIT #{limit}")
    List<MerchantResource> selectDueRules(@Param("limit") int limit);

    @Select("SELECT * FROM merchant_resource WHERE tenant_id = #{tenantId} AND resource_type = #{type} AND status = 1 ORDER BY id")
    List<MerchantResource> selectEnabledByTenantAndType(@Param("tenantId") Long tenantId, @Param("type") String type);

    @Select("SELECT * FROM merchant_resource WHERE tenant_id = #{tenantId} AND resource_type = #{type} AND name = #{name} LIMIT 1")
    MerchantResource selectByTenantTypeAndName(@Param("tenantId") Long tenantId,
                                                @Param("type") String type,
                                                @Param("name") String name);

    @Select("SELECT * FROM merchant_resource WHERE tenant_id = #{tenantId} AND resource_type = #{type} AND xy_goods_id = #{goodsId} LIMIT 1")
    MerchantResource selectByTenantTypeAndGoodsId(@Param("tenantId") Long tenantId,
                                                   @Param("type") String type,
                                                   @Param("goodsId") String goodsId);

    @Update("UPDATE merchant_resource SET last_run_time = NOW(3), scheduled_time = #{nextTime} WHERE id = #{id}")
    int updateNextRun(@Param("id") Long id, @Param("nextTime") LocalDateTime nextTime);
}
