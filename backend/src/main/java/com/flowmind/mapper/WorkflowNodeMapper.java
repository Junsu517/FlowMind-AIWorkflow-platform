package com.flowmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowmind.entity.WorkflowNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {
}
