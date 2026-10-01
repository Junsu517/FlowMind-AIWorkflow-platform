package com.flowmind.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ExecutionVO {

    private Long id;
    private Long workflowId;
    private String workflowName;
    private String status;
    private String triggerType;
    private JsonNode input;
    private JsonNode output;
    private Integer totalTokens;
    private BigDecimal totalCost;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
