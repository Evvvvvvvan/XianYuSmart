package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.controller.dto.BuyerProfileRespDTO;
import com.xianyusmart.entity.XianyuBuyerProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家关系资料 Mapper
 */
@Mapper
public interface XianyuBuyerProfileMapper extends BaseMapper<XianyuBuyerProfile> {

    @Select("SELECT * FROM xianyu_buyer_profile WHERE xianyu_account_id = #{accountId} " +
            "AND buyer_user_id = #{buyerUserId} LIMIT 1")
    XianyuBuyerProfile findByBuyer(@Param("accountId") Long accountId,
                                   @Param("buyerUserId") String buyerUserId);

    @Insert("INSERT INTO xianyu_buyer_profile (tenant_id, xianyu_account_id, buyer_user_id, " +
            "buyer_user_name, last_interaction_time) VALUES (#{tenantId}, #{accountId}, #{buyerUserId}, " +
            "#{buyerUserName}, #{interactionTime}) ON DUPLICATE KEY UPDATE " +
            "buyer_user_name = COALESCE(NULLIF(VALUES(buyer_user_name), ''), buyer_user_name), " +
            "last_interaction_time = GREATEST(COALESCE(last_interaction_time, VALUES(last_interaction_time)), " +
            "VALUES(last_interaction_time))")
    int touch(@Param("tenantId") Long tenantId,
              @Param("accountId") Long accountId,
              @Param("buyerUserId") String buyerUserId,
              @Param("buyerUserName") String buyerUserName,
              @Param("interactionTime") LocalDateTime interactionTime);

    @Select("<script>" +
            "SELECT profile.*, " +
            "(SELECT COUNT(*) FROM xianyu_chat_message message WHERE message.xianyu_account_id = profile.xianyu_account_id " +
            "AND message.sender_user_id = profile.buyer_user_id) AS message_count, " +
            "(SELECT COUNT(*) FROM xianyu_goods_order orders WHERE orders.xianyu_account_id = profile.xianyu_account_id " +
            "AND orders.buyer_user_id = profile.buyer_user_id) AS order_count, " +
            "(SELECT COALESCE(SUM(CAST(orders.total_price AS DECIMAL(12,2))), 0) FROM xianyu_goods_order orders " +
            "WHERE orders.xianyu_account_id = profile.xianyu_account_id AND orders.buyer_user_id = profile.buyer_user_id " +
            "AND orders.state = 1) AS total_amount " +
            "FROM xianyu_buyer_profile profile WHERE 1 = 1 " +
            "<if test='accountId != null'>AND profile.xianyu_account_id = #{accountId} </if>" +
            "<if test='blocked != null'>AND profile.automation_blocked = #{blocked} </if>" +
            "<if test='keyword != null and keyword != \"\"'>AND (profile.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.buyer_user_id LIKE CONCAT('%', #{keyword}, '%') OR profile.tags_json LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.note LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "ORDER BY profile.automation_blocked DESC, profile.last_interaction_time DESC, profile.id DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<BuyerProfileRespDTO> selectPage(@Param("accountId") Long accountId,
                                         @Param("keyword") String keyword,
                                         @Param("blocked") Integer blocked,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_buyer_profile profile WHERE 1 = 1 " +
            "<if test='accountId != null'>AND profile.xianyu_account_id = #{accountId} </if>" +
            "<if test='blocked != null'>AND profile.automation_blocked = #{blocked} </if>" +
            "<if test='keyword != null and keyword != \"\"'>AND (profile.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.buyer_user_id LIKE CONCAT('%', #{keyword}, '%') OR profile.tags_json LIKE CONCAT('%', #{keyword}, '%') " +
            "OR profile.note LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "</script>")
    long countPage(@Param("accountId") Long accountId,
                   @Param("keyword") String keyword,
                   @Param("blocked") Integer blocked);
}
