package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 第 1 周仅建表占位，执行逻辑由统筹的 engine 包实现 */
@Data
@TableName("execution_node_logs")
public class ExecutionNodeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long executionId;

    private String nodeKey;

    private String nodeName;

    private String nodeType;

    /** pending/running/success/failed/skipped */
    private String status;

    private String input;

    private String output;

    private Integer tokens;

    private Long durationMs;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
