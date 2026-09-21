package com.flowmind.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class GraphVO {

    private List<NodeDTO> nodes;
    private List<EdgeDTO> edges;
}
