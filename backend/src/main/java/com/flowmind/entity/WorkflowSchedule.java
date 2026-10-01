package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流定时计划（一工作流一条，uk_workflow）；Quartz 内存模式的 DB 真相源 */
@Data
@TableName("workflow_schedules")
public class WorkflowSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    /** 标准 6 段 cron */
    private String cron;

    private Integer enabled;

    /** 执行入参 JSON（空 = 用 input 节点字段默认值） */
    private String input;

    private LocalDateTime lastRunAt;

    private LocalDateTime createdAt;
}
