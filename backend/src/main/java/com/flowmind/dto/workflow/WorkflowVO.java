package com.flowmind.dto.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 列表场景带 nodeCount；单查/创建场景该字段为 null 不输出。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowVO {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long nodeCount;
}
