package com.flowmind.dto.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class ScheduleRequest {

    @NotBlank(message = "cron 不能为空")
    @Size(max = 64)
    private String cron;

    private Boolean enabled;

    /** 执行入参（空 = 用 input 节点字段默认值） */
    private Map<String, Object> input;
}
