package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.MerchantDistribution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商品分销结算Mapper
 */
@Mapper
public interface MerchantDistributionMapper extends BaseMapper<MerchantDistribution> {

    @Select("<script>SELECT * FROM merchant_distribution WHERE 1=1 " +
            "<if test='status != null'>AND status = #{status}</if> " +
            "<if test='settlementStatus != null'>AND settlement_status = #{settlementStatus}</if> " +
            "ORDER BY updated_time DESC LIMIT #{limit}</script>")
    List<MerchantDistribution> selectRecent(@Param("status") Integer status,
                                             @Param("settlementStatus") Integer settlementStatus,
                                             @Param("limit") int limit);

    @Select("SELECT * FROM merchant_distribution WHERE tenant_id = #{tenantId} AND supply_resource_id = #{supplyId} " +
            "AND material_resource_id = #{materialId} LIMIT 1")
    MerchantDistribution selectRelation(@Param("tenantId") Long tenantId,
                                        @Param("supplyId") Long supplyId,
                                        @Param("materialId") Long materialId);

    @Update("UPDATE merchant_distribution SET xianyu_account_id = #{accountId}, xy_goods_id = #{goodsId}, status = 1 " +
            "WHERE tenant_id = #{tenantId} AND material_resource_id = #{materialId}")
    int updatePublishedByMaterial(@Param("tenantId") Long tenantId,
                                  @Param("materialId") Long materialId,
                                  @Param("accountId") Long accountId,
                                  @Param("goodsId") String goodsId);

    @Update("UPDATE merchant_distribution SET settlement_status = 1, settlement_time = NOW(3) WHERE id = #{id} AND settlement_status = 0")
    int settle(@Param("id") Long id);
}
