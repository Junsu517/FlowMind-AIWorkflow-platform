package com.flowmind.service;

import com.flowmind.engine.ExecutionListener;
import com.flowmind.engine.model.NodeResult;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把引擎回调翻译为契约 v1.3 的 SSE 事件。
 * 注意：契约没有 node_failed 事件——节点失败经引擎 fail-fast 汇入 execution_failed 下发。
 * 事件 data 不含 prompt 全文（契约要求，node_started 只带 nodeKey/name）。
 * durationMs 由本类自测：引擎在 finally 中才写 durationMs，onNodeCompleted 回调时尚未设置。
 */
@RequiredArgsConstructor
public class SseExecutionListener implements ExecutionListener {

    private final Long executionId;
    private final SseEmitterRegistry registry;
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();

    @Override
    public void onNodeStarted(NodeResult node) {
        startTimes.put(node.getNodeKey(), System.currentTimeMillis());
        registry.send(executionId, "node_started",
                Map.of("nodeKey", safe(node.getNodeKey()), "name", safe(node.getNodeName())));
    }

    @Override
    public void onNodeCompleted(NodeResult node) {
        Long start = startTimes.remove(node.getNodeKey());
        long duration = start == null ? node.getDurationMs() : System.currentTimeMillis() - start;
        registry.send(executionId, "node_completed",
                Map.of("nodeKey", safe(node.getNodeKey()), "durationMs", duration));
    }

    @Override
    public void onNodeSkipped(NodeResult node) {
        registry.send(executionId, "node_skipped",
                Map.of("nodeKey", safe(node.getNodeKey()), "reason", safe(node.getErrorMessage())));
    }

    @Override
    public void onToken(String nodeKey, String delta) {
        registry.send(executionId, "token", Map.of("nodeKey", safe(nodeKey), "delta", safe(delta)));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
