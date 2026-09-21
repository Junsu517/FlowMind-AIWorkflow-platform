package com.flowmind.service;

import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.dto.workflow.EdgeDTO;
import com.flowmind.dto.workflow.NodeDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 整图保存前的图校验：节点 key 唯一、边两端节点存在、有且仅有一个 input 节点、
 * 无孤立节点（input 除外）、无环（Kahn 拓扑排序检测）。
 */
@Component
public class GraphValidator {

    private static final Set<String> NODE_TYPES = Set.of("input", "llm", "condition", "http");

    public void validate(List<NodeDTO> nodes, List<EdgeDTO> edges) {
        if (nodes == null || nodes.isEmpty()) {
            throw invalid("节点列表不能为空");
        }

        // 1. 节点 key 唯一、type 合法、有且仅有一个 input
        Set<String> keys = new HashSet<>();
        int inputCount = 0;
        for (NodeDTO node : nodes) {
            if (!keys.add(node.getNodeKey())) {
                throw invalid("节点 key 重复: " + node.getNodeKey());
            }
            if (!NODE_TYPES.contains(node.getType())) {
                throw invalid("不支持的节点类型: " + node.getType());
            }
            if ("input".equals(node.getType())) {
                inputCount++;
            }
        }
        if (inputCount != 1) {
            throw invalid("工作流必须有且仅有一个 input 节点（当前 " + inputCount + " 个）");
        }

        // 2. 边两端节点必须存在、无重复边，同时统计度数（孤立检测）与入度（不可达/环检测）
        Map<String, Integer> degree = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        Set<String> edgeKeys = new HashSet<>();
        keys.forEach(k -> {
            degree.put(k, 0);
            indegree.put(k, 0);
        });
        for (EdgeDTO edge : edges) {
            if (!keys.contains(edge.getSourceNodeKey())) {
                throw invalid("边 sourceNodeKey=" + edge.getSourceNodeKey() + " 指向不存在的节点");
            }
            if (!keys.contains(edge.getTargetNodeKey())) {
                throw invalid("边 targetNodeKey=" + edge.getTargetNodeKey() + " 指向不存在的节点");
            }
            String edgeKey = edge.getSourceNodeKey() + "->" + edge.getTargetNodeKey()
                    + "@" + (edge.getSourceHandle() == null ? "" : edge.getSourceHandle());
            if (!edgeKeys.add(edgeKey)) {
                throw invalid("存在重复边: " + edge.getSourceNodeKey() + "->" + edge.getTargetNodeKey()
                        + (edge.getSourceHandle() == null ? "" : "@" + edge.getSourceHandle()));
            }
            degree.merge(edge.getSourceNodeKey(), 1, Integer::sum);
            degree.merge(edge.getTargetNodeKey(), 1, Integer::sum);
            indegree.merge(edge.getTargetNodeKey(), 1, Integer::sum);
            adjacency.computeIfAbsent(edge.getSourceNodeKey(), k -> new ArrayList<>())
                    .add(edge.getTargetNodeKey());
        }

        // 3. 孤立节点（无任何边，input 除外）
        List<String> isolated = nodes.stream()
                .filter(n -> !"input".equals(n.getType()) && degree.get(n.getNodeKey()) == 0)
                .map(NodeDTO::getNodeKey)
                .collect(Collectors.toList());
        if (!isolated.isEmpty()) {
            throw invalid("存在孤立节点: " + String.join(", ", isolated));
        }

        // 4. 不可达节点（入度为 0 且非 input，从 input 出发永远到不了）
        List<String> unreachable = nodes.stream()
                .filter(n -> !"input".equals(n.getType()) && indegree.get(n.getNodeKey()) == 0)
                .map(NodeDTO::getNodeKey)
                .collect(Collectors.toList());
        if (!unreachable.isEmpty()) {
            throw invalid("存在不可达节点（入度为 0 且非 input）: " + String.join(", ", unreachable));
        }

        // 5. Kahn 拓扑排序检测环
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((k, d) -> {
            if (d == 0) {
                queue.add(k);
            }
        });
        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            processed.add(current);
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (processed.size() != keys.size()) {
            String cycleNodes = keys.stream()
                    .filter(k -> !processed.contains(k))
                    .collect(Collectors.joining(", "));
            throw invalid("图中存在环，涉及节点: " + cycleNodes);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_GRAPH, message);
    }
}
