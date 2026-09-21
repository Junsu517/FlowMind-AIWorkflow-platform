package com.flowmind.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NodeDTO {

    @NotBlank(message = "nodeKey 不能为空")
    @Size(max = 50)
    private String nodeKey;

    @NotBlank(message = "节点 type 不能为空")
    @Size(max = 20)
    private String type;

    @Size(max = 100)
    private String name;

    /** 按 API 契约的节点 config 结构，原样透传，具体字段由 engine 校验 */
    private JsonNode config;

    private Double positionX;

    private Double positionY;
}
