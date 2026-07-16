package com.xianyusmart.mapper;

import com.xianyusmart.entity.XianyuGoodsConfig;
import org.apache.ibatis.annotations.*;

/**
 * 商品配置Mapper
 */
@Mapper
public interface XianyuGoodsConfigMapper {
    
    /**
     * 根据账号ID和商品ID查询配置
     */
    @Select("SELECT * FROM xianyu_goods_config WHERE xianyu_account_id = #{accountId} AND xy_goods_id = #{xyGoodsId}")
    XianyuGoodsConfig selectByAccountAndGoodsId(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId);

    @Select("SELECT * FROM xianyu_goods_config WHERE xianyu_account_id = #{accountId}")
    java.util.List<XianyuGoodsConfig> selectByAccountId(@Param("accountId") Long accountId);
    
    /**
     * 插入配置
     */
    @Insert("INSERT INTO xianyu_goods_config (xianyu_account_id, xianyu_goods_id, xy_goods_id, xianyu_auto_delivery_on, xianyu_auto_reply_on, xianyu_auto_rate_on, xianyu_auto_polish_on, xianyu_auto_reply_context_on, xianyu_keyword_reply_on, human_intervention_on, human_intervention_minutes, fixed_material) " +
            "VALUES (#{xianyuAccountId}, #{xianyuGoodsId}, #{xyGoodsId}, #{xianyuAutoDeliveryOn}, #{xianyuAutoReplyOn}, #{xianyuAutoRateOn}, #{xianyuAutoPolishOn}, #{xianyuAutoReplyContextOn}, #{xianyuKeywordReplyOn}, #{humanInterventionOn}, #{humanInterventionMinutes}, #{fixedMaterial})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuGoodsConfig config);
    
    /**
     * 更新配置
     */
    @Update("UPDATE xianyu_goods_config SET xianyu_auto_delivery_on = #{xianyuAutoDeliveryOn}, " +
            "xianyu_auto_reply_on = #{xianyuAutoReplyOn}, " +
            "xianyu_auto_rate_on = #{xianyuAutoRateOn}, " +
            "xianyu_auto_polish_on = #{xianyuAutoPolishOn}, " +
            "xianyu_auto_reply_context_on = #{xianyuAutoReplyContextOn}, " +
            "xianyu_keyword_reply_on = #{xianyuKeywordReplyOn}, " +
            "human_intervention_on = #{humanInterventionOn}, " +
            "human_intervention_minutes = #{humanInterventionMinutes}, " +
            "fixed_material = #{fixedMaterial} WHERE id = #{id}")
    int update(XianyuGoodsConfig config);
    
    /**
     * 更新固定资料
     */
    @Update("UPDATE xianyu_goods_config SET fixed_material = #{fixedMaterial} WHERE xianyu_account_id = #{accountId} AND xy_goods_id = #{xyGoodsId}")
    int updateFixedMaterial(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("fixedMaterial") String fixedMaterial);

    @Select("SELECT * FROM xianyu_goods_config WHERE xianyu_auto_rate_on = 1 ORDER BY xianyu_account_id, id")
    java.util.List<XianyuGoodsConfig> selectAutoRateEnabled();

    @Select("SELECT * FROM xianyu_goods_config WHERE xianyu_auto_polish_on = 1 ORDER BY xianyu_account_id, id")
    java.util.List<XianyuGoodsConfig> selectAutoPolishEnabled();

    @Update("UPDATE xianyu_goods_config SET last_polish_time = #{polishTime} WHERE id = #{id} AND xianyu_auto_polish_on = 1")
    int updateLastPolishTime(@Param("id") Long id, @Param("polishTime") Long polishTime);
    
    /**
     * 根据账号ID删除配置
     */
    @Delete("DELETE FROM xianyu_goods_config WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
