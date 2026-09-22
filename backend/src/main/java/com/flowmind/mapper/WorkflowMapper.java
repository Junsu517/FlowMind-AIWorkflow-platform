package com.flowmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowmind.dto.workflow.WorkflowVO;
import com.flowmind.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {

    /**
     * 分页查当前用户的工作流，nodeCount 用子查询统计。
     */
    @Select("""
            SELECT w.id, w.user_id, w.name, w.description, w.status, w.created_at, w.updated_at,
                   (SELECT COUNT(*) FROM workflow_nodes n WHERE n.workflow_id = w.id) AS node_count
            FROM workflows w
            WHERE w.user_id = #{userId}
            ORDER BY w.updated_at DESC, w.id DESC
            """)
    IPage<WorkflowVO> selectPageWithNodeCount(IPage<WorkflowVO> page, @Param("userId") Long userId);
}
