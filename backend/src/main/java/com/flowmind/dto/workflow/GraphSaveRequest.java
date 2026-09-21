package com.flowmind.dto.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class GraphSaveRequest {

    @NotNull(message = "nodes 不能为空")
    @Valid
    private List<NodeDTO> nodes;

    @Valid
    private List<EdgeDTO> edges;
}
