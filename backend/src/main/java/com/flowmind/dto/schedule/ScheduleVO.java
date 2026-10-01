package com.flowmind.dto.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/** configured=false 表示未配置计划（200 返回，避免与 404 工作流不存在语义混淆） */
@Data
public class ScheduleVO {

    private boolean configured;
    private String cron;
    private Boolean enabled;
    private JsonNode input;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextFireTime;
}
