package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.MerchantTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商家运营任务Mapper
 */
@Mapper
public interface MerchantTaskMapper extends BaseMapper<MerchantTask> {

    @Select("<script>SELECT * FROM merchant_task WHERE 1=1 " +
            "<if test='taskType != null and taskType != \"\"'>AND task_type = #{taskType}</if> " +
            "<if test='status != null'>AND status = #{status}</if> ORDER BY created_time DESC LIMIT #{limit}</script>")
    List<MerchantTask> selectRecent(@Param("taskType") String taskType,
                                    @Param("status") Integer status,
                                    @Param("limit") int limit);

    // 直接返回任务汇总，概览页无需加载任务明细再计算。
    @Select("SELECT COUNT(*) AS taskCount, COALESCE(SUM(CASE WHEN status = -1 THEN 1 ELSE 0 END), 0) AS failedTaskCount FROM merchant_task")
    Map<String, Object> selectOverviewCounts();

    @Select("SELECT * FROM merchant_task WHERE ((status = 0 AND scheduled_time <= NOW(3)) " +
            "OR (status = -1 AND attempt_count < max_attempts AND next_retry_time <= NOW(3)) " +
            "OR (status = 1 AND attempt_count < max_attempts AND updated_time <= DATE_SUB(NOW(3), INTERVAL 10 MINUTE))) " +
            "ORDER BY scheduled_time, id LIMIT #{limit}")
    List<MerchantTask> selectDue(@Param("limit") int limit);

    @Update("UPDATE merchant_task SET status = 1, attempt_count = attempt_count + 1 " +
            "WHERE id = #{id} AND (status IN (0, -1) OR (status = 1 AND updated_time <= DATE_SUB(NOW(3), INTERVAL 10 MINUTE)))")
    int claim(@Param("id") Long id);

    @Update("UPDATE merchant_task SET status = 2, result_json = #{resultJson}, error_message = NULL, next_retry_time = NULL WHERE id = #{id}")
    int complete(@Param("id") Long id, @Param("resultJson") String resultJson);

    @Update("UPDATE merchant_task SET status = -1, error_message = #{errorMessage}, next_retry_time = #{nextRetryTime} WHERE id = #{id}")
    int fail(@Param("id") Long id, @Param("errorMessage") String errorMessage,
             @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("UPDATE merchant_task SET status = 0, attempt_count = 0, scheduled_time = NOW(3), next_retry_time = NULL, error_message = NULL WHERE id = #{id}")
    int requeue(@Param("id") Long id);
}
