package com.flowmind.dto.execution;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class ExecuteRequest {

    @NotNull(message = "input 不能为空")
    private Map<String, Object> input;
}
