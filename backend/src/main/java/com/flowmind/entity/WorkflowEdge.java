package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_edges")
public class WorkflowEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private String sourceNodeKey;

    private String targetNodeKey;

    /** 条件分支名，普通边为 null */
    private String sourceHandle;

    private LocalDateTime createdAt;
}
