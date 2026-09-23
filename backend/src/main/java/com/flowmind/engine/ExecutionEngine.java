package com.flowmind.engine;

import com.flowmind.engine.model.ExecutionReport;
import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;
import com.flowmind.engine.model.NodeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 顺序执行引擎（阶梯 1）：
 * 1. 拓扑排序（含环检测）
 * 2. 按拓扑序依次执行；条件节点运行时决定激活分支，未激活分支及其下游标记 skipped
 * 3. 任一节点失败 → 整体失败（fail-fast）
 * 汇聚节点安全性：激活判定按"任一活跃入边"，天然兼容多父节点场景。
 */
public class ExecutionEngine {

    private final Map<String, NodeExecutor> executors;
    private final TopologicalSorter sorter = new TopologicalSorter();
    private final TemplateRenderer renderer = new TemplateRenderer();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    public ExecutionEngine(Map<String, NodeExecutor> executors) {
        this.executors = executors;
    }

    public ExecutionReport execute(List<GraphNode> nodes, List<GraphEdge> edges,
                                   Map<String, Object> input, ExecutionListener listener) {
        long start = System.currentTimeMillis();
        ExecutionReport report = new ExecutionReport();

        Map<String, GraphNode> nodeMap = new HashMap<>();
        for (GraphNode n : nodes) {
            nodeMap.put(n.getNodeKey(), n);
        }
        Map<String, List<GraphEdge>> incoming = new HashMap<>();
        for (String key : nodeMap.keySet()) {
            incoming.put(key, new ArrayList<>());
        }
        for (GraphEdge e : edges) {
            incoming.computeIfAbsent(e.getTargetNodeKey(), k -> new ArrayList<>()).add(e);
        }

        List<String> order = sorter.sort(nodes, edges);

        ExecutionContext context = new ExecutionContext();
        context.putNodeOutput("input", input);

        Map<String, String> conditionChoice = new HashMap<>();
        java.util.Set<String> executed = new java.util.HashSet<>();
        int totalTokens = 0;

        for (String key : order) {
            GraphNode node = nodeMap.get(key);

            boolean active;
            if ("input".equals(node.getType())) {
                active = true;
            } else {
                active = hasActiveIncomingEdge(incoming.get(key), executed, conditionChoice);
            }

            if (!active) {
                NodeResult skipped = baseResult(node);
                skipped.setStatus(NodeResult.STATUS_SKIPPED);
                skipped.setErrorMessage("上游分支未激活");
                report.getNodeResults().add(skipped);
                if (listener != null) {
                    listener.onNodeSkipped(skipped);
                }
                continue;
            }

            NodeResult result = runNode(node, context, listener);
            report.getNodeResults().add(result);
            totalTokens += result.getTokens();

            if (NodeResult.STATUS_FAILED.equals(result.getStatus())) {
                report.setStatus(ExecutionReport.STATUS_FAILED);
                report.setErrorMessage(result.getErrorMessage());
                report.setOutput(context.snapshot());
                report.setTotalTokens(totalTokens);
                report.setDurationMs(System.currentTimeMillis() - start);
                return report;
            }

            executed.add(key);
            if ("condition".equals(node.getType())) {
                conditionChoice.put(key, String.valueOf(result.getOutput().get("matchedHandle")));
            }
        }

        report.setStatus(ExecutionReport.STATUS_SUCCESS);
        report.setOutput(context.snapshot());
        report.setTotalTokens(totalTokens);
        report.setDurationMs(System.currentTimeMillis() - start);
        return report;
    }

    /** 节点是否被激活：存在任一条来自已执行节点的边，且（源为条件时）handle 匹配。 */
    private boolean hasActiveIncomingEdge(List<GraphEdge> inEdges, java.util.Set<String> executed,
                                          Map<String, String> conditionChoice) {
        if (inEdges == null || inEdges.isEmpty()) {
            return false;
        }
        for (GraphEdge e : inEdges) {
            String src = e.getSourceNodeKey();
            if (!executed.contains(src)) {
                continue; // 源节点未执行成功或已跳过
            }
            String choice = conditionChoice.get(src);
            if (choice == null) {
                return true; // 普通节点边：源执行成功即激活
            }
            if (e.getSourceHandle() == null || choice.equals(e.getSourceHandle())) {
                return true; // 条件边：handle 匹配才激活
            }
        }
        return false;
    }

    private NodeResult runNode(GraphNode node, ExecutionContext context, ExecutionListener listener) {
        long t0 = System.currentTimeMillis();
        NodeResult result = baseResult(node);
        result.setStatus(NodeResult.STATUS_RUNNING);
        if (listener != null) {
            listener.onNodeStarted(result);
        }

        try {
            Map<String, Object> renderedConfig = renderer.renderMap(
                    node.getConfig() == null ? Map.of() : node.getConfig(), context.snapshot());
            result.setInput(renderedConfig);

            Map<String, Object> output;
            int tokens = 0;
            switch (node.getType()) {
                case "input" -> output = (Map<String, Object>) context.getNodeOutput("input");
                case "condition" -> output = conditionEvaluator.evaluate(renderedConfig);
                default -> {
                    NodeExecutor executor = executors.get(node.getType());
                    if (executor == null) {
                        throw new EngineException("未注册的节点执行器: " + node.getType());
                    }
                    Consumer<String> sink = listener == null ? delta -> {
                    }
                            : delta -> listener.onToken(node.getNodeKey(), delta);
                    ExecutorResult res = executor.execute(
                            new NodeExecutionContext(node.getNodeKey(), node.getName(),
                                    node.getType(), renderedConfig, context, sink));
                    output = res.getOutput();
                    tokens = res.getTokens();
                }
            }

            context.putNodeOutput(node.getNodeKey(), output);
            result.setOutput(output);
            result.setTokens(tokens);
            result.setStatus(NodeResult.STATUS_SUCCESS);
            result.setDurationMs(System.currentTimeMillis() - t0);
            if (listener != null) {
                listener.onNodeCompleted(result);
            }
        } catch (Exception ex) {
            result.setStatus(NodeResult.STATUS_FAILED);
            result.setErrorMessage(ex.getMessage());
            result.setDurationMs(System.currentTimeMillis() - t0);
            if (listener != null) {
                listener.onNodeFailed(result);
            }
        }
        return result;
    }

    private NodeResult baseResult(GraphNode node) {
        NodeResult r = new NodeResult();
        r.setNodeKey(node.getNodeKey());
        r.setNodeName(node.getName());
        r.setNodeType(node.getType());
        return r;
    }
}
