package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.CycleException;
import com.flowmind.engine.ExecutionEngine;
import com.flowmind.engine.model.ExecutionReport;
import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;
import com.flowmind.entity.Execution;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶梯 6 执行器：从 ExecutionService 拆出的"按 executionId 跑一次执行"。
 * 线程池模式（thread）与 MQ 消费者（mqueue）共用同一入口，行为一致：
 * 幂等（非 queued 跳过）→ running → 重载图与入参 → 引擎 → 落库 → SSE 终态 → 释放分布式锁。
 * 系统异常时回置 queued 并上抛（供 MQ 重试）；业务失败（引擎返回 failed）落库不重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRunner {

    static final String LOCK_PREFIX = "lock:execute:";
    /** 锁 TTL：覆盖最长执行（FC 3 轮 × 60s LLM + 工具）的保守值，终态即释放 */
    static final long LOCK_TTL_SECONDS = 600;

    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final ExecutionMapper executionMapper;
    private final ExecutionEngine executionEngine;
    private final ExecutionPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final SseEmitterRegistry sseRegistry;
    private final DistributedLockService lockService;

    /**
     * @throws RuntimeException 系统异常（行已回置 queued，可重试）
     */
    public void runByExecutionId(Long executionId) {
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            log.warn("执行记录不存在，消息丢弃: executionId={}", executionId);
            return;
        }
        if (!"queued".equals(execution.getStatus())) {
            log.info("幂等跳过（执行行非 queued，可能已处理）: executionId={}, status={}",
                    executionId, execution.getStatus());
            return;
        }
        executionMapper.update(null, new LambdaUpdateWrapper<Execution>()
                .eq(Execution::getId, executionId)
                .set(Execution::getStatus, "running")
                // startedAt 更新为真正开始执行的时刻：也是启动恢复区分
                // "本 JVM 正在跑"与"上一进程孤儿"的依据（恢复只翻早于 JVM 启动的行）
                .set(Execution::getStartedAt, LocalDateTime.now()));

        ExecutionReport report;
        try {
            List<GraphNode> nodes = loadNodes(execution.getWorkflowId());
            List<GraphEdge> edges = loadEdges(execution.getWorkflowId());
            if (nodes.isEmpty()) {
                report = failedReport("工作流未保存图，无法执行");
            } else {
                Map<String, Object> input = readInputMap(execution.getInput());
                sseRegistry.send(executionId, "execution_started",
                        Map.of("executionId", executionId, "totalNodes", nodes.size()));
                SseExecutionListener listener = new SseExecutionListener(executionId, sseRegistry);
                report = executionEngine.execute(nodes, edges, input, listener);
            }
        } catch (CycleException e) {
            report = failedReport(e.getMessage());
        } catch (Exception e) {
            // 系统异常：回置 queued 供 MQ 重试（线程池模式由调用方转 failed）
            executionMapper.update(null, new LambdaUpdateWrapper<Execution>()
                    .eq(Execution::getId, executionId).eq(Execution::getStatus, "running")
                    .set(Execution::getStatus, "queued"));
            throw new RuntimeException("执行系统异常: " + e.getMessage(), e);
        }

        try {
            persistenceService.persist(executionId, report);
        } catch (Exception e) {
            log.error("执行结果落库失败（重试将因非 queued 跳过，行由启动恢复兜底）executionId={}", executionId, e);
        }
        sendTerminalEvent(executionId, report);
        releaseLock(execution);
    }

    /** 终态兜底标记（线程池异常/confirm 失败/DLQ 共用）：幂等，仅非终态时生效。 */
    public void markFailed(Long executionId, String errorMessage) {
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null || isTerminal(execution.getStatus())) {
            return;
        }
        executionMapper.update(null, new LambdaUpdateWrapper<Execution>()
                .eq(Execution::getId, executionId)
                .set(Execution::getStatus, "failed")
                .set(Execution::getErrorMessage, truncate(errorMessage))
                .set(Execution::getFinishedAt, LocalDateTime.now()));
        sseRegistry.send(executionId, "execution_failed",
                Map.of("error", errorMessage == null ? "unknown" : errorMessage));
        sseRegistry.complete(executionId);
        releaseLock(execution);
    }

    private boolean isTerminal(String status) {
        return "success".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private void sendTerminalEvent(Long executionId, ExecutionReport report) {
        if (ExecutionReport.STATUS_SUCCESS.equals(report.getStatus())) {
            sseRegistry.send(executionId, "execution_completed", Map.of(
                    "output", report.getOutput() == null ? Map.of() : report.getOutput(),
                    "totalTokens", report.getTotalTokens(),
                    "durationMs", report.getDurationMs()));
        } else {
            sseRegistry.send(executionId, "execution_failed",
                    Map.of("error", report.getErrorMessage() == null ? "unknown" : report.getErrorMessage()));
        }
        sseRegistry.complete(executionId);
    }

    private void releaseLock(Execution execution) {
        if (execution.getWorkflowId() != null) {
            lockService.release(LOCK_PREFIX + execution.getWorkflowId(), String.valueOf(execution.getId()));
        }
    }

    private ExecutionReport failedReport(String errorMessage) {
        ExecutionReport report = new ExecutionReport();
        report.setStatus(ExecutionReport.STATUS_FAILED);
        report.setErrorMessage(errorMessage == null ? "unknown" : errorMessage);
        return report;
    }

    private List<GraphNode> loadNodes(Long workflowId) {
        return nodeMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowNode::getId))
                .stream()
                .map(n -> new GraphNode(n.getNodeKey(), n.getType(), n.getName(), toConfigMap(n.getConfig())))
                .toList();
    }

    private List<GraphEdge> loadEdges(Long workflowId) {
        return edgeMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowEdge::getId))
                .stream()
                .map(e -> new GraphEdge(e.getSourceNodeKey(), e.getTargetNodeKey(), e.getSourceHandle()))
                .toList();
    }

    private Map<String, Object> toConfigMap(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("节点 config 解析失败，按空配置处理: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> readInputMap(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(inputJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String truncate(String message) {
        return message != null && message.length() > 500 ? message.substring(0, 500) : message;
    }
}
