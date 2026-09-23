package com.flowmind.engine;

import com.flowmind.engine.model.ExecutionReport;
import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;
import com.flowmind.engine.model.NodeResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 并行执行引擎（阶梯 3）：
 * 1. 拓扑排序校验（含环检测）
 * 2. 动态调度：未决入边数降到 0 且存在活跃入边的节点进入就绪队列，
 *    线程池并行执行（CompletionService 收集完成事件）
 * 3. 条件节点运行时决定激活分支；惰性跳过判定——节点所有入边均为死边才 skipped
 *    （汇聚节点多父时，任一活跃路径可达即执行）
 * 4. 任一节点失败 → 整体失败（fail-fast：不再提交新节点，在跑的等自然结束，
 *    未执行的标记 skipped）
 *
 * 每次执行使用独立的 Run 内部状态，引擎实例可安全支持并发执行。
 */
public class ExecutionEngine {

    private static final int POOL_SIZE = 4;

    private final Map<String, NodeExecutor> executors;
    private final TopologicalSorter sorter = new TopologicalSorter();
    private final TemplateRenderer renderer = new TemplateRenderer();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    public ExecutionEngine(Map<String, NodeExecutor> executors) {
        this.executors = executors;
    }

    public ExecutionReport execute(List<GraphNode> nodes, List<GraphEdge> edges,
                                   Map<String, Object> input, ExecutionListener listener) {
        return new Run(nodes, edges, input, listener).go();
    }

    /** 单次执行的完整状态，保证并发执行互不干扰。 */
    private class Run {

        private final List<GraphNode> nodes;
        private final List<GraphEdge> edges;
        private final Map<String, Object> input;
        private final ExecutionListener listener;
        private final ExecutionContext context = new ExecutionContext();

        private final Map<String, GraphNode> nodeMap = new HashMap<>();
        private final Map<String, List<Integer>> outgoing = new HashMap<>();
        private final Map<String, List<Integer>> incoming = new HashMap<>();
        private final Map<Integer, Boolean> edgeActive = new ConcurrentHashMap<>();
        private final Map<String, String> conditionChoice = new ConcurrentHashMap<>();
        private final Map<String, NodeResult> results = new ConcurrentHashMap<>();
        private final Map<String, Integer> pending = new HashMap<>();

        private final AtomicInteger totalTokens = new AtomicInteger();
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final AtomicReference<String> failureMsg = new AtomicReference<>();
        private final ConcurrentLinkedQueue<String> ready = new ConcurrentLinkedQueue<>();

        Run(List<GraphNode> nodes, List<GraphEdge> edges, Map<String, Object> input,
            ExecutionListener listener) {
            this.nodes = nodes;
            this.edges = edges;
            this.input = input;
            this.listener = listener;
        }

        ExecutionReport go() {
            long start = System.currentTimeMillis();
            ExecutionReport report = new ExecutionReport();

            for (GraphNode n : nodes) {
                nodeMap.put(n.getNodeKey(), n);
            }
            List<String> order = sorter.sort(nodes, edges);
            int total = order.size();

            for (String key : order) {
                outgoing.put(key, new ArrayList<>());
                incoming.put(key, new ArrayList<>());
            }
            for (int i = 0; i < edges.size(); i++) {
                GraphEdge e = edges.get(i);
                outgoing.computeIfAbsent(e.getSourceNodeKey(), k -> new ArrayList<>()).add(i);
                incoming.computeIfAbsent(e.getTargetNodeKey(), k -> new ArrayList<>()).add(i);
            }
            for (String key : order) {
                pending.put(key, incoming.getOrDefault(key, List.of()).size());
            }

            context.putNodeOutput("input", input);

            String inputKey = null;
            for (String key : order) {
                if ("input".equals(nodeMap.get(key).getType())) {
                    inputKey = key;
                    break;
                }
            }
            if (inputKey == null) {
                throw new EngineException("工作流缺少 input 节点");
            }

            ExecutorService pool = Executors.newFixedThreadPool(Math.min(POOL_SIZE, total));
            CompletionService<NodeResult> completion = new ExecutorCompletionService<>(pool);
            ready.add(inputKey);
            AtomicInteger submitted = new AtomicInteger();
            AtomicInteger resolved = new AtomicInteger();

            try {
                while (resolved.get() < total) {
                    String key;
                    while ((key = ready.poll()) != null) {
                        if (failed.get()) {
                            markSkipped(key, "执行已失败终止");
                        } else {
                            GraphNode node = nodeMap.get(key);
                            completion.submit(() -> runNode(node));
                            submitted.incrementAndGet();
                        }
                    }
                    if (submitted.get() <= resolved.get()) {
                        break; // 无在途任务且无就绪节点（环已被拓扑排序排除，理论上不可达）
                    }
                    Future<NodeResult> f = completion.take();
                    NodeResult r = f.get();
                    resolved.incrementAndGet();
                    results.put(r.getNodeKey(), r);
                    totalTokens.addAndGet(r.getTokens());
                    if (NodeResult.STATUS_FAILED.equals(r.getStatus())) {
                        failed.set(true);
                        failureMsg.set(r.getErrorMessage());
                    } else if ("condition".equals(r.getNodeType())) {
                        conditionChoice.put(r.getNodeKey(),
                                String.valueOf(r.getOutput().get("matchedHandle")));
                    }
                    resolveDownstream(r.getNodeKey());
                }
            } catch (Exception ex) {
                failed.set(true);
                failureMsg.set("调度异常: " + ex.getMessage());
            } finally {
                pool.shutdownNow();
            }

            List<NodeResult> orderedResults = new ArrayList<>();
            for (String key : order) {
                NodeResult r = results.get(key);
                if (r == null) {
                    r = baseResult(nodeMap.get(key));
                    r.setStatus(NodeResult.STATUS_SKIPPED);
                    r.setErrorMessage("执行终止未调度");
                }
                orderedResults.add(r);
            }

            report.setStatus(failed.get() ? ExecutionReport.STATUS_FAILED : ExecutionReport.STATUS_SUCCESS);
            report.setErrorMessage(failed.get() ? failureMsg.get() : null);
            report.setOutput(context.snapshot());
            report.setTotalTokens(totalTokens.get());
            report.setNodeResults(orderedResults);
            report.setDurationMs(System.currentTimeMillis() - start);
            return report;
        }

        /** 源节点已决（成功/失败/跳过），解析其出边并级联推进下游。 */
        private void resolveDownstream(String srcKey) {
            String choice = conditionChoice.get(srcKey);
            NodeResult srcResult = results.get(srcKey);
            boolean srcActive = srcResult != null
                    && NodeResult.STATUS_SUCCESS.equals(srcResult.getStatus());
            for (Integer edgeIdx : outgoing.getOrDefault(srcKey, List.of())) {
                GraphEdge e = edges.get(edgeIdx);
                boolean active = srcActive && (choice == null
                        || e.getSourceHandle() == null || choice.equals(e.getSourceHandle()));
                edgeActive.put(edgeIdx, active);
                String target = e.getTargetNodeKey();
                int remain = pending.merge(target, -1, Integer::sum);
                if (remain == 0) {
                    if (hasActiveIncoming(target)) {
                        ready.add(target);
                    } else {
                        markSkipped(target, "上游分支未激活");
                    }
                }
            }
        }

        private boolean hasActiveIncoming(String nodeKey) {
            for (Integer edgeIdx : incoming.getOrDefault(nodeKey, List.of())) {
                if (Boolean.TRUE.equals(edgeActive.get(edgeIdx))) {
                    return true;
                }
            }
            return false;
        }

        private void markSkipped(String key, String reason) {
            NodeResult skipped = baseResult(nodeMap.get(key));
            skipped.setStatus(NodeResult.STATUS_SKIPPED);
            skipped.setErrorMessage(reason);
            results.putIfAbsent(key, skipped);
            if (listener != null) {
                listener.onNodeSkipped(skipped);
            }
            // 级联：跳过的节点其出边全为死边
            resolveDownstream(key);
        }

        private NodeResult runNode(GraphNode node) {
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
}
