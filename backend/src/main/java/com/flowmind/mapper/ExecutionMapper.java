package com.flowmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowmind.entity.Execution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ExecutionMapper extends BaseMapper<Execution> {

    @Select("""
            SELECT COUNT(*) AS total_executions,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(total_cost), 0) AS total_cost
            FROM executions
            WHERE user_id = #{userId}
            """)
    Map<String, Object> selectUserUsage(@Param("userId") Long userId);

    @Select("""
            SELECT DATE_FORMAT(started_at, '%Y-%m-%d') AS date, COUNT(*) AS count
            FROM executions
            WHERE user_id = #{userId} AND started_at >= #{since}
            GROUP BY DATE_FORMAT(started_at, '%Y-%m-%d')
            ORDER BY date
            """)
    List<Map<String, Object>> selectExecutionsByDay(@Param("userId") Long userId,
                                                    @Param("since") LocalDateTime since);

    // ---------------- 阶梯 9 仪表盘聚合 ----------------

    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failed_count,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(SUM(total_cost), 0) AS total_cost
            FROM executions
            WHERE user_id = #{userId}
            """)
    Map<String, Object> selectDashboardSummary(@Param("userId") Long userId);

    @Select("""
            SELECT DATE_FORMAT(started_at, '%Y-%m-%d') AS date,
                   COUNT(*) AS executions,
                   COALESCE(SUM(total_tokens), 0) AS tokens
            FROM executions
            WHERE user_id = #{userId} AND started_at >= #{since}
            GROUP BY DATE_FORMAT(started_at, '%Y-%m-%d')
            ORDER BY date
            """)
    List<Map<String, Object>> selectDashboardDaily(@Param("userId") Long userId,
                                                   @Param("since") LocalDateTime since);

    @Select("""
            SELECT trigger_type AS source, COUNT(*) AS count
            FROM executions
            WHERE user_id = #{userId}
            GROUP BY trigger_type
            """)
    List<Map<String, Object>> selectDashboardSourceDist(@Param("userId") Long userId);

    @Select("""
            SELECT e.workflow_id AS workflowId, w.name AS name, COUNT(*) AS executions
            FROM executions e
            JOIN workflows w ON w.id = e.workflow_id
            WHERE e.user_id = #{userId}
            GROUP BY e.workflow_id, w.name
            ORDER BY executions DESC
            LIMIT 5
            """)
    List<Map<String, Object>> selectDashboardTopWorkflows(@Param("userId") Long userId);
}
