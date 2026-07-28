package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuKamiExternalRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface XianyuKamiExternalRequestMapper extends BaseMapper<XianyuKamiExternalRequest> {

    @Insert("""
            INSERT IGNORE INTO xianyu_kami_external_request
                (kami_config_id, xianyu_account_id, order_id, request_token, quantity,
                 request_status, attempt_count, create_time, update_time)
            VALUES
                (#{request.kamiConfigId}, #{request.xianyuAccountId}, #{request.orderId},
                 #{request.requestToken}, #{request.quantity}, 'PROCESSING', 1, NOW(3), NOW(3))
            """)
    int insertIfAbsent(@Param("request") XianyuKamiExternalRequest request);

    @Select("""
            SELECT *
            FROM xianyu_kami_external_request
            WHERE kami_config_id = #{kamiConfigId} AND order_id = #{orderId}
            LIMIT 1
            """)
    XianyuKamiExternalRequest findByOrder(@Param("kamiConfigId") Long kamiConfigId,
                                          @Param("orderId") String orderId);

    @Select("""
            SELECT COUNT(*)
            FROM xianyu_kami_external_request
            WHERE kami_config_id = #{kamiConfigId}
              AND request_status IN ('PROCESSING', 'REVIEW_REQUIRED')
            """)
    int countUnsettledByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Update("""
            UPDATE xianyu_kami_external_request
            SET request_status = 'PROCESSING',
                attempt_count = attempt_count + 1,
                exception_revision = exception_revision + 1,
                error_message = NULL,
                update_time = NOW(3)
            WHERE id = #{id}
              AND attempt_count < 3
              AND (
                request_status = 'FAILED'
                OR (request_status = 'PROCESSING' AND update_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE))
              )
            """)
    int claimRetry(@Param("id") Long id);

    @Update("""
            UPDATE xianyu_kami_external_request
            SET request_status = 'SUCCESS',
                response_excerpt = #{responseExcerpt},
                error_message = NULL,
                update_time = NOW(3)
            WHERE id = #{id} AND request_status = 'PROCESSING'
            """)
    int markSuccess(@Param("id") Long id, @Param("responseExcerpt") String responseExcerpt);

    @Update("""
            UPDATE xianyu_kami_external_request
            SET request_status = #{status},
                error_message = #{errorMessage},
                exception_revision = exception_revision + 1,
                update_time = NOW(3)
            WHERE id = #{id} AND request_status = 'PROCESSING'
            """)
    int markFailure(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("errorMessage") String errorMessage);
}
