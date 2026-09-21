package com.flowmind.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ToolVO {

    private Long id;
    private String name;
    private String displayName;
    private String description;
    private String method;
    private JsonNode paramsSchema;
}
