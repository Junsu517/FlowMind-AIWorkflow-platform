package com.flowmind.engine;

import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Kahn 拓扑排序：返回执行序列，检测到环时抛 CycleException。 */
public class TopologicalSorter {

    public List<String> sort(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphNode n : nodes) {
            indegree.put(n.getNodeKey(), 0);
            adjacency.put(n.getNodeKey(), new ArrayList<>());
        }
        for (GraphEdge e : edges) {
            adjacency.get(e.getSourceNodeKey()).add(e.getTargetNodeKey());
            indegree.merge(e.getTargetNodeKey(), 1, Integer::sum);
        }

        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((k, d) -> {
            if (d == 0) {
                queue.add(k);
            }
        });

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            order.add(cur);
            for (String next : adjacency.get(cur)) {
                indegree.merge(next, -1, Integer::sum);
                if (indegree.get(next) == 0) {
                    queue.add(next);
                }
            }
        }

        if (order.size() != nodes.size()) {
            Set<String> inCycle = new HashSet<>(indegree.keySet());
            inCycle.removeAll(order);
            throw new CycleException("图中存在环，涉及节点: " + String.join(", ", inCycle));
        }
        return order;
    }
}
