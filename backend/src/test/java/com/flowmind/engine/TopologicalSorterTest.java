package com.flowmind.engine;

import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologicalSorterTest {

    private final TopologicalSorter sorter = new TopologicalSorter();

    private GraphNode node(String key) {
        return new GraphNode(key, "llm", key, Map.of());
    }

    @Test
    void 线性图按序输出() {
        List<String> order = sorter.sort(
                List.of(node("a"), node("b"), node("c")),
                List.of(new GraphEdge("a", "b", null), new GraphEdge("b", "c", null)));
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void 分支汇聚图拓扑有序且父节点在前() {
        List<String> order = sorter.sort(
                List.of(node("input"), node("a"), node("b"), node("d")),
                List.of(new GraphEdge("input", "a", null), new GraphEdge("input", "b", null),
                        new GraphEdge("a", "d", null), new GraphEdge("b", "d", null)));
        assertEquals(0, order.indexOf("input"));
        assertTrue(order.indexOf("a") < order.indexOf("d"));
        assertTrue(order.indexOf("b") < order.indexOf("d"));
        assertEquals(3, order.indexOf("d"));
    }

    @Test
    void 检测到环抛CycleException并指明节点() {
        CycleException ex = assertThrows(CycleException.class, () -> sorter.sort(
                List.of(node("a"), node("b")),
                List.of(new GraphEdge("a", "b", null), new GraphEdge("b", "a", null))));
        assertTrue(ex.getMessage().contains("a"));
        assertTrue(ex.getMessage().contains("b"));
    }
}
