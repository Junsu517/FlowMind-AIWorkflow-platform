package com.flowmind.dto.publish;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class PublicRunRequest {

    @NotNull(message = "input 不能为空")
    private Map<String, Object> input;
}
