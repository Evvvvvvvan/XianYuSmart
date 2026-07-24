package com.xianyusmart.mapper;

import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.controller.dto.DashboardStatsRespDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 商品订单Mapper
 */
@Mapper
public interface XianyuGoodsOrderMapper {

    /**
     * 单次查询聚合经营指标与异常待办，减少首页数据库往返。
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM xianyu_account) AS account_count,
              (SELECT COUNT(*) FROM xianyu_goods) AS item_count,
              (SELECT COUNT(*) FROM xianyu_goods WHERE status = 0) AS selling_item_count,
              (SELECT COUNT(*) FROM xianyu_goods WHERE status = 1) AS off_shelf_item_count,
              (SELECT COUNT(*) FROM xianyu_goods WHERE status = 2) AS sold_item_count,
              (SELECT COALESCE(SUM(CAST(total_price AS DECIMAL(12, 2))), 0)
                 FROM xianyu_goods_order WHERE state = 1 AND create_time >= CURRENT_DATE) AS today_revenue,
              (SELECT COUNT(*) FROM xianyu_goods_order
                 WHERE state = 1 AND create_time >= CURRENT_DATE) AS today_delivery_count,
              (SELECT COUNT(*) FROM xianyu_goods_auto_reply_record
                 WHERE state = 1 AND create_time >= CURRENT_DATE) AS today_reply_count,
              (SELECT COUNT(*) FROM xianyu_goods_order
                 WHERE delivery_status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')) AS pending_task_count,
              (SELECT COUNT(*) FROM xianyu_goods_order
                 WHERE delivery_status = 'REVIEW_REQUIRED') AS review_required_count,
              (SELECT COUNT(*) FROM xianyu_goods_order
                 WHERE delivery_status = 'FAILED') AS failed_task_count,
              (SELECT COUNT(*) FROM xianyu_kami_item WHERE status = 0) AS available_kami_count,
              (SELECT COUNT(*) FROM xianyu_kami_config c
                 WHERE c.alert_enabled = 1 AND (
                   (COALESCE(c.alert_threshold_type, 1) = 1 AND
                     (SELECT COUNT(*) FROM xianyu_kami_item k WHERE k.kami_config_id = c.id AND k.status = 0) < COALESCE(c.alert_threshold_value, 10))
                   OR (c.alert_threshold_type = 2 AND c.total_count > 0 AND
                     (SELECT COUNT(*) FROM xianyu_kami_item k WHERE k.kami_config_id = c.id AND k.status = 0) * 100 < c.total_count * COALESCE(c.alert_threshold_value, 10))
                 )) AS low_stock_config_count
            """)
    DashboardStatsRespDTO selectDashboardStats();
    
    @Insert("INSERT INTO xianyu_goods_order (xianyu_account_id, xianyu_goods_id, xy_goods_id, pnm_id, order_id, buyer_user_id, buyer_user_name, sid, content, state, fail_reason, confirm_state, goods_title, sku_name, sku_id, order_create_time, pay_success_time, consign_time, total_price, buy_num, delivery_status, expected_quantity, delivery_channel) " +
            "VALUES (#{xianyuAccountId}, #{xianyuGoodsId}, #{xyGoodsId}, #{pnmId}, #{orderId}, #{buyerUserId}, #{buyerUserName}, #{sid}, #{content}, #{state}, #{failReason}, #{confirmState}, #{goodsTitle}, #{skuName}, #{skuId}, #{orderCreateTime}, #{paySuccessTime}, #{consignTime}, #{totalPrice}, COALESCE(#{buyNum}, 1), COALESCE(#{deliveryStatus}, CASE WHEN #{state} = 1 THEN 'COMPLETED' WHEN #{state} = -1 THEN 'FAILED' ELSE 'PENDING' END), COALESCE(#{expectedQuantity}, COALESCE(#{buyNum}, 1)), #{deliveryChannel}) " +
            "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuGoodsOrder record);
    
    @Select("SELECT * FROM xianyu_goods_order WHERE xianyu_account_id = #{accountId} ORDER BY create_time DESC")
    List<XianyuGoodsOrder> selectByAccountId(@Param("accountId") Long accountId);
    
    @Delete("DELETE FROM xianyu_goods_order WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
    
    @Select("<script>" +
            "SELECT r.*, " +
            "g.title as goods_title " +
            "FROM xianyu_goods_order r " +
            "LEFT JOIN xianyu_goods g ON r.xy_goods_id = g.xy_good_id " +
            "WHERE r.xianyu_account_id = #{accountId} " +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND r.xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (g.title LIKE CONCAT('%', #{keyword}, '%') OR r.sku_name LIKE CONCAT('%', #{keyword}, '%') OR r.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') OR r.content LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='deliveryStatuses != null and !deliveryStatuses.isEmpty()'>" +
            "AND r.delivery_status IN <foreach collection='deliveryStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach> " +
            "</if>" +
            "ORDER BY r.create_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "xianyuAccountId", column = "xianyu_account_id"),
        @Result(property = "xianyuGoodsId", column = "xianyu_goods_id"),
        @Result(property = "xyGoodsId", column = "xy_goods_id"),
        @Result(property = "pnmId", column = "pnm_id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "buyerUserId", column = "buyer_user_id"),
        @Result(property = "buyerUserName", column = "buyer_user_name"),
        @Result(property = "sid", column = "sid"),
        @Result(property = "content", column = "content"),
        @Result(property = "state", column = "state"),
        @Result(property = "failReason", column = "fail_reason"),
        @Result(property = "deliveryStatus", column = "delivery_status"),
        @Result(property = "lastErrorMessage", column = "last_error_message"),
        @Result(property = "confirmState", column = "confirm_state"),
        @Result(property = "rateStatus", column = "rate_status"),
        @Result(property = "rateTime", column = "rate_time"),
        @Result(property = "rateContent", column = "rate_content"),
        @Result(property = "rateSource", column = "rate_source"),
        @Result(property = "createTime", column = "create_time"),
        @Result(property = "goodsTitle", column = "goods_title"),
        @Result(property = "skuName", column = "sku_name"),
        @Result(property = "skuId", column = "sku_id"),
        @Result(property = "orderCreateTime", column = "order_create_time"),
        @Result(property = "paySuccessTime", column = "pay_success_time"),
        @Result(property = "consignTime", column = "consign_time"),
        @Result(property = "totalPrice", column = "total_price"),
        @Result(property = "buyNum", column = "buy_num")
    })
    List<XianyuGoodsOrder> selectByAccountIdWithPage(
            @Param("accountId") Long accountId,
            @Param("xyGoodsId") String xyGoodsId,
            @Param("keyword") String keyword,
            @Param("deliveryStatuses") List<String> deliveryStatuses,
            @Param("limit") int limit,
            @Param("offset") long offset);
    
    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_goods_order r " +
            "LEFT JOIN xianyu_goods g ON r.xy_goods_id = g.xy_good_id " +
            "WHERE r.xianyu_account_id = #{accountId} " +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND r.xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (g.title LIKE CONCAT('%', #{keyword}, '%') OR r.sku_name LIKE CONCAT('%', #{keyword}, '%') OR r.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') OR r.content LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='deliveryStatuses != null and !deliveryStatuses.isEmpty()'>" +
            "AND r.delivery_status IN <foreach collection='deliveryStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach> " +
            "</if>" +
            "</script>")
    long countByAccountId(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId,
                          @Param("keyword") String keyword, @Param("deliveryStatuses") List<String> deliveryStatuses);
    
    @Update("UPDATE xianyu_goods_order SET state = #{state}, delivery_status = CASE WHEN #{state} = 1 THEN 'COMPLETED' WHEN #{state} = -1 THEN 'FAILED' ELSE delivery_status END WHERE id = #{id}")
    int updateState(@Param("id") Long id, @Param("state") Integer state);
    
    @Update("UPDATE xianyu_goods_order SET state = #{state}, content = #{content}, delivery_status = CASE WHEN #{state} = 1 THEN 'COMPLETED' WHEN #{state} = -1 THEN 'FAILED' ELSE delivery_status END WHERE id = #{id}")
    int updateStateAndContent(@Param("id") Long id, @Param("state") Integer state, @Param("content") String content);

    @Update("UPDATE xianyu_goods_order SET state = #{state}, content = #{content}, fail_reason = #{failReason}, delivery_status = CASE WHEN #{state} = 1 THEN 'COMPLETED' WHEN #{state} = -1 THEN 'FAILED' ELSE delivery_status END WHERE id = #{id}")
    int updateStateContentAndFailReason(@Param("id") Long id, @Param("state") Integer state, @Param("content") String content, @Param("failReason") String failReason);
    
    @Select("SELECT * FROM xianyu_goods_order WHERE xianyu_account_id = #{accountId} AND xy_goods_id = #{xyGoodsId} AND order_id = #{orderId} LIMIT 1")
    XianyuGoodsOrder selectByOrderId(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("orderId") String orderId);

    @Select("SELECT * FROM xianyu_goods_order WHERE xianyu_account_id = #{accountId} AND order_id = #{orderId} LIMIT 1")
    XianyuGoodsOrder selectByAccountIdAndOrderId(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Select("SELECT * FROM xianyu_goods_order WHERE id = #{id}")
    XianyuGoodsOrder selectById(@Param("id") Long id);

    @Select("SELECT * FROM xianyu_goods_order WHERE " +
            "((delivery_status IN ('PENDING', 'RETRY_WAIT') AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))) " +
            "OR (delivery_status = 'PROCESSING' AND lease_expire_time < NOW(3))) " +
            "AND delivery_message_state NOT IN (3, 4, 5) " +
            "ORDER BY create_time ASC LIMIT #{limit} FOR UPDATE")
    List<XianyuGoodsOrder> lockDueTasks(@Param("limit") int limit);

    @Update("<script>UPDATE xianyu_goods_order SET delivery_status = 'PROCESSING', lease_owner = #{workerId}, " +
            "lease_expire_time = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND), attempt_count = attempt_count + 1 " +
            "WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int claimTasks(@Param("ids") List<Long> ids, @Param("workerId") String workerId,
                   @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE xianyu_goods_order SET delivery_status = 'COMPLETED', delivered_quantity = expected_quantity, " +
            "next_retry_time = NULL, lease_owner = NULL, lease_expire_time = NULL, last_error_code = NULL, last_error_message = NULL WHERE id = #{id}")
    int completeTask(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET delivery_status = #{status}, next_retry_time = #{nextRetryTime}, " +
            "lease_owner = NULL, lease_expire_time = NULL, last_error_code = 'DELIVERY_FAILED', last_error_message = #{errorMessage} " +
            "WHERE id = #{id} AND delivery_status NOT IN ('REVIEW_REQUIRED', 'COMPLETED')")
    int retryOrFailTask(@Param("id") Long id, @Param("status") String status,
                        @Param("nextRetryTime") java.time.LocalDateTime nextRetryTime,
                        @Param("errorMessage") String errorMessage);

    @Update("UPDATE xianyu_goods_order SET delivery_status = 'REVIEW_REQUIRED', next_retry_time = NULL, " +
            "lease_owner = NULL, lease_expire_time = NULL, last_error_code = 'DELIVERY_UNCERTAIN', last_error_message = #{errorMessage} WHERE id = #{id}")
    int markTaskReviewRequired(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    @Update("UPDATE xianyu_goods_order SET delivery_status = 'PENDING', next_retry_time = NOW(3), " +
            "lease_owner = NULL, lease_expire_time = NULL WHERE id = #{id} AND state <> 1 AND delivery_status IN ('FAILED', 'RETRY_WAIT')")
    int requeueTask(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET delivery_status = 'PENDING', next_retry_time = NOW(3), " +
            "state = 0, fail_reason = NULL, attempt_count = 0, lease_owner = NULL, lease_expire_time = NULL, " +
            "last_error_code = NULL, last_error_message = NULL " +
            "WHERE id = #{id} AND xianyu_account_id = #{accountId} AND state <> 1 AND delivery_status = 'FAILED'")
    int requeueFailedTask(@Param("id") Long id, @Param("accountId") Long accountId);
    
    @Update("UPDATE xianyu_goods_order SET confirm_state = 1 WHERE xianyu_account_id = #{accountId} AND order_id = #{orderId}")
    int updateConfirmState(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Update("UPDATE xianyu_goods_order SET rate_status = #{rateStatus}, rate_time = NOW(3) WHERE xianyu_account_id = #{accountId} AND order_id = #{orderId}")
    int updateRateStatus(@Param("accountId") Long accountId, @Param("orderId") String orderId,
                         @Param("rateStatus") Integer rateStatus);

    @Update("UPDATE xianyu_goods_order SET rate_status = #{rateStatus}, rate_time = NOW(3), " +
            "rate_content = #{rateContent}, rate_source = #{rateSource} " +
            "WHERE xianyu_account_id = #{accountId} AND order_id = #{orderId}")
    int updateRateResult(@Param("accountId") Long accountId, @Param("orderId") String orderId,
                         @Param("rateStatus") Integer rateStatus, @Param("rateContent") String rateContent,
                         @Param("rateSource") String rateSource);

    @Update("UPDATE xianyu_goods_order SET delivery_message_content = #{content}, delivery_message_state = 0, " +
            "delivery_message_attempt_count = 0, delivery_message_next_retry_time = NOW(3), " +
            "state = 1, content = #{content}, fail_reason = NULL, delivery_status = 'COMPLETED', " +
            "delivered_quantity = expected_quantity, next_retry_time = NULL, lease_owner = NULL, " +
            "lease_expire_time = NULL, last_error_code = NULL, last_error_message = NULL WHERE id = #{id}")
    int prepareDeliveryMessage(@Param("id") Long id, @Param("content") String content);

    @Update("UPDATE xianyu_goods_order SET delivery_message_content = #{content}, delivery_message_state = #{holdState}, " +
            "delivery_message_attempt_count = 0, delivery_message_next_retry_time = " +
            "DATE_ADD(NOW(3), INTERVAL 30 MINUTE) " +
            "WHERE id = #{id}")
    int holdDeliveryMessage(@Param("id") Long id, @Param("content") String content,
                            @Param("holdState") int holdState);

    @Update("UPDATE xianyu_goods_order SET delivery_message_state = 0, delivery_message_next_retry_time = NOW(3), " +
            "state = 1, content = delivery_message_content, fail_reason = NULL, delivery_status = 'COMPLETED', " +
            "delivered_quantity = expected_quantity, next_retry_time = NULL, lease_owner = NULL, " +
            "lease_expire_time = NULL, last_error_code = NULL, last_error_message = NULL " +
            "WHERE id = #{id} AND delivery_message_content IS NOT NULL AND delivery_message_state IN (3, 5)")
    int activateDeliveryMessage(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET delivery_message_content = NULL, delivery_message_state = 0, " +
            "delivery_message_attempt_count = 0, delivery_message_next_retry_time = NULL " +
            "WHERE id = #{id} AND delivery_message_state IN (3, 4, 5)")
    int cancelHeldDeliveryMessage(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET confirm_state = 1, delivery_message_state = 5, " +
            "delivery_message_next_retry_time = NULL WHERE xianyu_account_id = #{accountId} " +
            "AND order_id = #{orderId} AND delivery_message_state = 4")
    int confirmFixedHeldDeliveryMessage(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Update("UPDATE xianyu_goods_order SET delivery_message_content = NULL, delivery_message_state = 0, " +
            "delivery_message_attempt_count = 0, delivery_message_next_retry_time = NULL " +
            "WHERE delivery_message_state = 4 AND delivery_message_next_retry_time <= NOW(3) LIMIT #{limit}")
    int cancelStaleFixedHeldDeliveryMessages(@Param("limit") int limit);

    @Update("UPDATE xianyu_goods_order SET delivery_status = 'REVIEW_REQUIRED', " +
            "last_error_code = 'DELIVERY_HELD_TIMEOUT', last_error_message = '卡密已暂存但履约结果未确认', " +
            "delivery_message_next_retry_time = NULL, lease_owner = NULL, lease_expire_time = NULL " +
            "WHERE delivery_message_state = 3 AND delivery_message_next_retry_time <= NOW(3) " +
            "AND NOT EXISTS (SELECT 1 FROM xianyu_kami_usage_record r JOIN xianyu_kami_item i " +
            "ON i.id = r.kami_item_id WHERE r.xianyu_account_id = xianyu_goods_order.xianyu_account_id " +
            "AND r.order_id = xianyu_goods_order.order_id AND r.delivery_status = 'DELIVERED' " +
            "AND i.order_id = xianyu_goods_order.order_id AND i.status = 1) LIMIT #{limit}")
    int markStaleCardHeldMessagesReviewRequired(@Param("limit") int limit);

    @Update("UPDATE xianyu_goods_order SET delivery_message_state = 1, delivery_message_next_retry_time = NULL WHERE id = #{id}")
    int markDeliveryMessageSent(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET delivery_message_state = 2, " +
            "delivery_message_next_retry_time = DATE_ADD(NOW(3), INTERVAL 2 MINUTE) WHERE id = #{id} " +
            "AND delivery_message_content IS NOT NULL AND delivery_message_state IN (0, 2) " +
            "AND (delivery_message_next_retry_time IS NULL OR delivery_message_next_retry_time <= NOW(3))")
    int claimDeliveryMessage(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET delivery_message_state = 0, " +
            "delivery_message_attempt_count = delivery_message_attempt_count + 1, " +
            "delivery_message_next_retry_time = #{nextRetryTime} WHERE id = #{id} AND delivery_message_state IN (0, 2)")
    int deferDeliveryMessage(@Param("id") Long id, @Param("nextRetryTime") java.time.LocalDateTime nextRetryTime);

    @Select("SELECT * FROM xianyu_goods_order WHERE delivery_message_content IS NOT NULL AND delivery_message_state IN (0, 2) " +
            "AND (delivery_message_next_retry_time IS NULL OR delivery_message_next_retry_time <= NOW(3)) " +
            "ORDER BY delivery_message_next_retry_time ASC LIMIT #{limit}")
    List<XianyuGoodsOrder> selectDueDeliveryMessages(@Param("limit") int limit);

    @Select("SELECT o.* FROM xianyu_goods_order o WHERE o.delivery_message_content IS NOT NULL AND (" +
            "(o.delivery_message_state = 3 AND EXISTS (SELECT 1 FROM xianyu_kami_usage_record r " +
            "JOIN xianyu_kami_item i ON i.id = r.kami_item_id WHERE r.xianyu_account_id = o.xianyu_account_id " +
            "AND r.order_id = o.order_id AND r.delivery_status = 'DELIVERED' " +
            "AND i.order_id = o.order_id AND i.status = 1)) OR " +
            "o.delivery_message_state = 5) " +
            "ORDER BY o.create_time ASC LIMIT #{limit}")
    List<XianyuGoodsOrder> selectCommittedHeldDeliveryMessages(@Param("limit") int limit);

    @Select("SELECT o.* FROM xianyu_goods_order o WHERE o.state = 1 AND o.receipt_follow_up_completed = 0 " +
            "AND (o.receipt_follow_up_next_time IS NULL OR o.receipt_follow_up_next_time <= NOW(3)) " +
            "AND EXISTS (SELECT 1 FROM xianyu_goods_auto_delivery_config c " +
            "WHERE c.xianyu_account_id = o.xianyu_account_id AND c.xy_goods_id = o.xy_goods_id " +
            "AND (c.sku_id IS NULL OR c.sku_id = o.sku_id) " +
            "AND c.receipt_follow_up_messages IS NOT NULL AND c.receipt_follow_up_messages NOT IN ('', '[]')) " +
            "ORDER BY o.receipt_follow_up_next_time ASC, o.create_time ASC LIMIT #{limit}")
    List<XianyuGoodsOrder> selectDueReceiptFollowUps(@Param("limit") int limit);

    @Update("UPDATE xianyu_goods_order SET buyer_confirmed_receipt = 1 WHERE id = #{id}")
    int markBuyerConfirmedReceipt(@Param("id") Long id);

    @Update("UPDATE xianyu_goods_order SET receipt_follow_up_next_time = DATE_ADD(NOW(3), INTERVAL 2 MINUTE) " +
            "WHERE id = #{id} AND receipt_follow_up_completed = 0 AND receipt_follow_up_sent_count = #{sentCount} " +
            "AND (receipt_follow_up_next_time IS NULL OR receipt_follow_up_next_time <= NOW(3))")
    int claimReceiptFollowUp(@Param("id") Long id, @Param("sentCount") Integer sentCount);

    @Update("UPDATE xianyu_goods_order SET receipt_follow_up_sent_count = #{sentCount}, " +
            "receipt_follow_up_completed = #{completed}, receipt_follow_up_next_time = #{nextTime} " +
            "WHERE id = #{id} AND receipt_follow_up_completed = 0 AND receipt_follow_up_sent_count = #{expectedSentCount}")
    int updateReceiptFollowUpProgress(@Param("id") Long id, @Param("sentCount") Integer sentCount,
                                      @Param("completed") Integer completed,
                                      @Param("nextTime") java.time.LocalDateTime nextTime,
                                      @Param("expectedSentCount") Integer expectedSentCount);

    @Update("UPDATE xianyu_goods_order SET receipt_follow_up_next_time = #{nextTime} " +
            "WHERE id = #{id} AND receipt_follow_up_completed = 0")
    int deferReceiptFollowUp(@Param("id") Long id, @Param("nextTime") java.time.LocalDateTime nextTime);
    
    @Select("SELECT * FROM xianyu_goods_order WHERE xianyu_account_id = #{accountId} AND pnm_id = #{pnmId}")
    XianyuGoodsOrder selectByPnmId(@Param("accountId") Long accountId, @Param("pnmId") String pnmId);

    @Select("SELECT COUNT(*) FROM xianyu_goods_order WHERE create_time >= CURRENT_DATE - INTERVAL 1 DAY AND create_time < CURRENT_DATE")
    int countYesterdayOrders();

    @Select("SELECT COUNT(*) FROM xianyu_goods_order WHERE state = 1")
    int countDeliverySuccess();

    @Select("SELECT COUNT(*) FROM xianyu_goods_order WHERE state = -1")
    int countDeliveryFail();

    @Select("SELECT COUNT(*) FROM xianyu_goods_order")
    int countAllOrders();

    @Select("SELECT COUNT(*) FROM xianyu_goods_order WHERE date(create_time) = #{date}")
    int countOrdersByDate(@Param("date") String date);

    @Select("<script>" +
            "SELECT r.*, g.title as goods_title " +
            "FROM xianyu_goods_order r " +
            "LEFT JOIN xianyu_goods g ON r.xy_goods_id = g.xy_good_id " +
            "WHERE 1=1 " +
            "<if test='accountId != null'>" +
            "AND r.xianyu_account_id = #{accountId} " +
            "</if>" +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND r.xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='orderStatus != null'>" +
            "AND r.state = #{orderStatus} " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (g.title LIKE CONCAT('%', #{keyword}, '%') OR r.sku_name LIKE CONCAT('%', #{keyword}, '%') OR r.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') OR r.content LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY r.create_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "xianyuAccountId", column = "xianyu_account_id"),
        @Result(property = "xianyuGoodsId", column = "xianyu_goods_id"),
        @Result(property = "xyGoodsId", column = "xy_goods_id"),
        @Result(property = "pnmId", column = "pnm_id"),
        @Result(property = "orderId", column = "order_id"),
        @Result(property = "buyerUserId", column = "buyer_user_id"),
        @Result(property = "buyerUserName", column = "buyer_user_name"),
        @Result(property = "sid", column = "sid"),
        @Result(property = "content", column = "content"),
        @Result(property = "state", column = "state"),
        @Result(property = "failReason", column = "fail_reason"),
        @Result(property = "confirmState", column = "confirm_state"),
        @Result(property = "rateStatus", column = "rate_status"),
        @Result(property = "rateTime", column = "rate_time"),
        @Result(property = "createTime", column = "create_time"),
        @Result(property = "goodsTitle", column = "goods_title"),
        @Result(property = "skuName", column = "sku_name"),
        @Result(property = "skuId", column = "sku_id"),
        @Result(property = "orderCreateTime", column = "order_create_time"),
        @Result(property = "paySuccessTime", column = "pay_success_time"),
        @Result(property = "consignTime", column = "consign_time"),
        @Result(property = "totalPrice", column = "total_price"),
        @Result(property = "buyNum", column = "buy_num")
    })
    List<XianyuGoodsOrder> selectByConditionWithPage(
            @Param("accountId") Long accountId,
            @Param("xyGoodsId") String xyGoodsId,
            @Param("orderStatus") Integer orderStatus,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_goods_order r " +
            "LEFT JOIN xianyu_goods g ON r.xy_goods_id = g.xy_good_id " +
            "WHERE 1=1 " +
            "<if test='accountId != null'>" +
            "AND r.xianyu_account_id = #{accountId} " +
            "</if>" +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND r.xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='orderStatus != null'>" +
            "AND r.state = #{orderStatus} " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (g.title LIKE CONCAT('%', #{keyword}, '%') OR r.sku_name LIKE CONCAT('%', #{keyword}, '%') OR r.buyer_user_name LIKE CONCAT('%', #{keyword}, '%') OR r.content LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "</script>")
    long countByCondition(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("orderStatus") Integer orderStatus, @Param("keyword") String keyword);

    @Select("SELECT COUNT(*) FROM xianyu_goods_order WHERE state = 1 AND date(create_time) = #{date}")
    int countDeliverySuccessByDate(@Param("date") String date);

    @Select("SELECT COUNT(*) FROM xianyu_goods_order WHERE state = -1 AND date(create_time) = #{date}")
    int countDeliveryFailByDate(@Param("date") String date);

    @Select("SELECT COALESCE(SUM(CAST(total_price AS DECIMAL(12, 2))), 0) FROM xianyu_goods_order WHERE state = 1 AND confirm_state = 1")
    double sumDeliverySuccessAmount();

    @Update("UPDATE xianyu_goods_order SET sku_name = #{skuName} WHERE id = #{id}")
    int updateSkuName(@Param("id") Long id, @Param("skuName") String skuName);

    @Update("UPDATE xianyu_goods_order SET buyer_user_name = #{buyerUserName}, order_create_time = #{orderCreateTime}, pay_success_time = #{paySuccessTime}, consign_time = #{consignTime}, sku_name = #{skuName}, sku_id = COALESCE(#{skuId}, sku_id), goods_title = #{goodsTitle}, total_price = #{totalPrice}, buy_num = #{buyNum} WHERE id = #{id}")
    int updateOrderDetail(@Param("id") Long id, @Param("buyerUserName") String buyerUserName, @Param("orderCreateTime") String orderCreateTime, @Param("paySuccessTime") String paySuccessTime, @Param("consignTime") String consignTime, @Param("skuName") String skuName, @Param("skuId") String skuId, @Param("goodsTitle") String goodsTitle, @Param("totalPrice") String totalPrice, @Param("buyNum") Integer buyNum);

    @Select("SELECT COALESCE(SUM(CAST(total_price AS DECIMAL(12, 2))), 0) FROM xianyu_goods_order WHERE state = 1 AND confirm_state = 1 AND date(create_time) = #{date}")
    double sumDeliverySuccessAmountByDate(@Param("date") String date);

    @Select("SELECT COALESCE(SUM(CAST(total_price AS DECIMAL(12, 2))), 0) FROM xianyu_goods_order WHERE state = 1 AND confirm_state = 1 AND date(create_time) >= #{startDate} AND date(create_time) <= #{endDate}")
    double sumDeliverySuccessAmountByDateRange(@Param("startDate") String startDate, @Param("endDate") String endDate);
}
