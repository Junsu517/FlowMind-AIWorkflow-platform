package com.flowmind.engine;

import com.flowmind.engine.model.ExecutionReport;
import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;
import com.flowmind.engine.model.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineTest {

    private ExecutionEngine engine() {
        return new ExecutionEngine(Map.of(
                "llm", new NodeExecutor() {
                    @Override
                    public String type() {
                        return "llm";
                    }

                    @Override
                    public ExecutorResult execute(NodeExecutionContext ctx) {
                        String prompt = String.valueOf(ctx.getConfig().get("prompt"));
                        ctx.getTokenSink().accept("你");
                        ctx.getTokenSink().accept("好");
                        return new ExecutorResult(Map.of("text", prompt + " 的回答"), 2);
                    }
                },
                "http", new NodeExecutor() {
                    @Override
                    public String type() {
                        return "http";
                    }

                    @Override
                    public ExecutorResult execute(NodeExecutionContext ctx) {
                        Map<String, Object> params = castMap(ctx.getConfig().get("params"));
                        String city = String.valueOf(params.get("city"));
                        return new ExecutorResult(Map.of("data", Map.of("city", city, "temp", 26)), 0);
                    }
                }));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private GraphNode node(String key, String type, String name, Map<String, Object> config) {
        return new GraphNode(key, type, name, config);
    }

    private final Map<String, Object> inputConfig =
            Map.of("fields", List.of(Map.of("key", "city", "label", "城市", "type", "text", "default", "南昌")));

    @Test
    void 线性流程input到http到llm全部成功() {
        List<GraphNode> nodes = List.of(
                node("input_1", "input", "输入", inputConfig),
                node("http_1", "http", "天气", Map.of("params", Map.of("city", "{{input.city}}"))),
                node("llm_1", "llm", "AI", Map.of("prompt", "给{{input.city}}的建议", "output_key", "text")));
        List<GraphEdge> edges = List.of(
                new GraphEdge("input_1", "http_1", null),
                new GraphEdge("http_1", "llm_1", null));

        ExecutionReport report = engine().execute(nodes, edges, Map.of("city", "南昌"), null);

        assertEquals(ExecutionReport.STATUS_SUCCESS, report.getStatus());
        assertEquals(3, report.getNodeResults().size());
        assertTrue(report.getNodeResults().stream().allMatch(r -> NodeResult.STATUS_SUCCESS.equals(r.getStatus())));
        assertEquals(2, report.getTotalTokens());
        assertEquals("给南昌的建议 的回答", castMap(report.getOutput().get("llm_1")).get("text"));
        assertEquals(26, castMap(castMap(report.getOutput().get("http_1")).get("data")).get("temp"));
    }

    @Test
    void 条件分支只执行命中路径() {
        List<GraphNode> nodes = List.of(
                node("input_1", "input", "输入", inputConfig),
                node("cond_1", "condition", "判断", Map.of(
                        "rules", List.of(Map.of("left", "{{input.city}}", "operator", "equals", "right", "南昌", "handle", "nc")),
                        "default_handle", "other")),
                node("http_1", "http", "天气", Map.of("params", Map.of("city", "{{input.city}}"))),
                node("llm_1", "llm", "AI", Map.of("prompt", "建议", "output_key", "text")));
        List<GraphEdge> edges = List.of(
                new GraphEdge("input_1", "cond_1", null),
                new GraphEdge("cond_1", "http_1", "nc"),
                new GraphEdge("cond_1", "llm_1", "other"));

        ExecutionReport report = engine().execute(nodes, edges, Map.of("city", "南昌"), null);

        assertEquals(ExecutionReport.STATUS_SUCCESS, report.getStatus());
        Map<String, String> statusByKey = new java.util.HashMap<>();
        report.getNodeResults().forEach(r -> statusByKey.put(r.getNodeKey(), r.getStatus()));
        assertEquals("success", statusByKey.get("http_1"));
        assertEquals("skipped", statusByKey.get("llm_1"));
    }

    @Test
    void 汇聚节点双父一活跃一死分支仍执行() {
        List<GraphNode> nodes = List.of(
                node("input_1", "input", "输入", inputConfig),
                node("cond_1", "condition", "判断", Map.of(
                        "rules", List.of(Map.of("left", "{{input.city}}", "operator", "equals", "right", "北京", "handle", "bj")),
                        "default_handle", "other")),
                node("b", "http", "死分支", Map.of("params", Map.of("city", "{{input.city}}"))),
                node("c", "llm", "活跃分支", Map.of("prompt", "{{input.city}}", "output_key", "text")),
                node("d", "llm", "汇聚", Map.of("prompt", "{{c.text}}", "output_key", "text")));
        List<GraphEdge> edges = List.of(
                new GraphEdge("input_1", "cond_1", null),
                new GraphEdge("cond_1", "b", "bj"),
                new GraphEdge("cond_1", "c", "other"),
                new GraphEdge("b", "d", null),
                new GraphEdge("c", "d", null));

        ExecutionReport report = engine().execute(nodes, edges, Map.of("city", "南昌"), null);

        assertEquals(ExecutionReport.STATUS_SUCCESS, report.getStatus());
        Map<String, String> statusByKey = new java.util.HashMap<>();
        report.getNodeResults().forEach(r -> statusByKey.put(r.getNodeKey(), r.getStatus()));
        assertEquals("skipped", statusByKey.get("b"));
        assertEquals("success", statusByKey.get("c"));
        // 汇聚节点 d：b 分支死了但 c 分支活跃，必须执行
        assertEquals("success", statusByKey.get("d"));
    }

    @Test
    void 节点失败整体失败failFast() {
        ExecutionEngine failing = new ExecutionEngine(Map.of("llm", new NodeExecutor() {
            @Override
            public String type() {
                return "llm";
            }

            @Override
            public ExecutorResult execute(NodeExecutionContext ctx) {
                throw new RuntimeException("DeepSeek 调用超时");
            }
        }));
        List<GraphNode> nodes = List.of(
                node("input_1", "input", "输入", inputConfig),
                node("llm_1", "llm", "AI", Map.of("prompt", "p", "output_key", "text")));
        List<GraphEdge> edges = List.of(new GraphEdge("input_1", "llm_1", null));

        ExecutionReport report = failing.execute(nodes, edges, Map.of("city", "南昌"), null);

        assertEquals(ExecutionReport.STATUS_FAILED, report.getStatus());
        assertNotNull(report.getErrorMessage());
        NodeResult llm = report.getNodeResults().get(1);
        assertEquals(NodeResult.STATUS_FAILED, llm.getStatus());
        assertTrue(llm.getErrorMessage().contains("超时"));
    }

    @Test
    void 环图抛CycleException() {
        List<GraphNode> nodes = List.of(
                node("a", "llm", "A", Map.of("prompt", "p", "output_key", "o")),
                node("b", "llm", "B", Map.of("prompt", "p", "output_key", "o")));
        List<GraphEdge> edges = List.of(
                new GraphEdge("a", "b", null), new GraphEdge("b", "a", null));
        assertThrows(CycleException.class, () -> engine().execute(nodes, edges, Map.of(), null));
    }

    @Test
    void 回调时durationMs已设置() {
        ExecutionEngine slowEngine = new ExecutionEngine(Map.of("llm", new NodeExecutor() {
            @Override
            public String type() {
                return "llm";
            }

            @Override
            public ExecutorResult execute(NodeExecutionContext ctx) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
                return new ExecutorResult(Map.of("text", "ok"), 0);
            }
        }));
        final long[] cbDuration = {-1};
        ExecutionListener listener = new ExecutionListener() {
            @Override
            public void onNodeCompleted(NodeResult node) {
                if ("llm_1".equals(node.getNodeKey())) {
                    cbDuration[0] = node.getDurationMs();
                }
            }
        };
        List<GraphNode> nodes = List.of(
                node("input_1", "input", "输入", inputConfig),
                node("llm_1", "llm", "AI", Map.of("prompt", "p", "output_key", "text")));
        List<GraphEdge> edges = List.of(new GraphEdge("input_1", "llm_1", null));

        slowEngine.execute(nodes, edges, Map.of("city", "南昌"), listener);

        assertTrue(cbDuration[0] >= 50, "回调时 durationMs 应已设置，实际 " + cbDuration[0]);
    }

    @Test
    void 监听器收到完整事件序列() {
        List<String> events = new ArrayList<>();
        ExecutionListener listener = new ExecutionListener() {
            @Override
            public void onNodeStarted(NodeResult node) {
                events.add("start:" + node.getNodeKey());
            }

            @Override
            public void onNodeCompleted(NodeResult node) {
                events.add("done:" + node.getNodeKey());
            }

            @Override
            public void onToken(String nodeKey, String delta) {
                events.add("token:" + nodeKey + ":" + delta);
            }
        };
        List<GraphNode> nodes = List.of(
                node("input_1", "input", "输入", inputConfig),
                node("llm_1", "llm", "AI", Map.of("prompt", "p", "output_key", "text")));
        List<GraphEdge> edges = List.of(new GraphEdge("input_1", "llm_1", null));

        engine().execute(nodes, edges, Map.of("city", "南昌"), listener);

        assertTrue(events.contains("start:input_1"));
        assertTrue(events.contains("done:input_1"));
        assertTrue(events.contains("token:llm_1:你"));
        assertTrue(events.contains("done:llm_1"));
        assertTrue(events.indexOf("start:llm_1") > events.indexOf("done:input_1"));
    }
}
