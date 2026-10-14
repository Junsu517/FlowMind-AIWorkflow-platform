package com.flowmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 第 1 周仅建表占位，执行逻辑由统筹的 engine 包实现 */
@Data
@TableName("executions")
public class Execution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long userId;

    /** running/success/failed/cancelled */
    private String status;

    /** manual/scheduled（阶梯 7） */
    private String triggerType;

    private String input;

    /** output 是 jsqlparser 保留字：不转义时 PaginationInnerInterceptor 的 count 优化解析失败（WARN）
     *  ——反引号转义后 MySQL 正常执行、jsqlparser 正常解析 */
    @TableField("`output`")
    private String output;

    private Integer totalTokens;

    private BigDecimal totalCost;

    private Long durationMs;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
