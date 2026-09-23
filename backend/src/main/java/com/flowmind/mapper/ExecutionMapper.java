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
}
