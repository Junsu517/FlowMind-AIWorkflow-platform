package com.flowmind.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class NodeLogVO {

    private String nodeKey;
    private String nodeName;
    private String nodeType;
    private String status;
    private JsonNode input;
    private JsonNode output;
    private Integer tokens;
    private Long durationMs;
    private String errorMessage;
}
