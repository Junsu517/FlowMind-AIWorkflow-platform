package com.flowmind.dto.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow 统一结构（契约 v1.2）：列表/创建/详情/更新响应一致，均含 nodeCount。
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
