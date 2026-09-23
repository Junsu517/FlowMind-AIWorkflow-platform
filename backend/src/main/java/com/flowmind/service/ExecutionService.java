package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.ErrorCode;
import com.flowmind.common.UserContext;
import com.flowmind.dto.execution.ExecutionVO;
import com.flowmind.dto.execution.NodeLogVO;
import com.flowmind.dto.workflow.PageVO;
import com.flowmind.engine.CycleException;
import com.flowmind.engine.ExecutionEngine;
import com.flowmind.engine.model.ExecutionReport;
import com.flowmind.engine.model.GraphEdge;
import com.flowmind.engine.model.GraphNode;
import com.flowmind.engine.model.NodeResult;
import com.flowmind.entity.Execution;
import com.flowmind.entity.ExecutionNodeLog;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.ExecutionNodeLogMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    /** execute 限流：每用户每分钟 10 次（契约 v1.3） */
    private static final int EXECUTE_RATE_LIMIT = 10;
    private static final int EXECUTE_RATE_WINDOW_SECONDS = 60;

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final ExecutionMapper executionMapper;
    private final ExecutionNodeLogMapper logMapper;
    private final ExecutionEngine executionEngine;
    private final ExecutionPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.ThreadPoolExecutor executionThreadPool;
    private final RateLimitService rateLimitService;
    private final SseEmitterRegistry sseRegistry;

    /**
     * 提交执行：校验归属/图/入参/并发限制，落 running 行后交线程池异步跑，立即返回 executionId。
     */
    public Long submit(Long workflowId, Map<String, Object> input) {
        Long userId = UserContext.getUserId();
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
        }
        if (!workflow.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行他人的工作流");
        }
        if (!rateLimitService.tryAcquire("rl:execute:" + userId,
                EXECUTE_RATE_LIMIT, EXECUTE_RATE_WINDOW_SECONDS)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, workflowId).orderByAsc(WorkflowNode::getId));
        List<WorkflowEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdge>()
                .eq(WorkflowEdge::getWorkflowId, workflowId).orderByAsc(WorkflowEdge::getId));
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_GRAPH, "工作流未保存图，无法执行");
        }

        Map<String, Object> mergedInput = mergeInputWithDefaults(findInputNode(nodes), input);

        Long running = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getWorkflowId, workflowId)
                .eq(Execution::getStatus, "running"));
        if (running != null && running > 0) {
            throw new BusinessException(ErrorCode.WORKFLOW_RUNNING, "该工作流已有执行在运行，请稍后再试");
        }

        Execution execution = new Execution();
        execution.setWorkflowId(workflowId);
        execution.setUserId(userId);
        execution.setStatus("running");
        execution.setInput(writeJson(mergedInput));
        execution.setStartedAt(java.time.LocalDateTime.now());
        executionMapper.insert(execution);

        Long executionId = execution.getId();
        List<GraphNode> graphNodes = toGraphNodes(nodes);
        List<GraphEdge> graphEdges = edges.stream()
                .map(e -> new GraphEdge(e.getSourceNodeKey(), e.getTargetNodeKey(), e.getSourceHandle()))
                .toList();
        try {
            executionThreadPool.execute(() -> runAsync(executionId, graphNodes, graphEdges, mergedInput));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 队列已满：把 running 行回置为 failed，避免永久卡 409
            execution.setStatus("failed");
            execution.setErrorMessage("执行队列已满，请稍后重试");
            execution.setFinishedAt(java.time.LocalDateTime.now());
            executionMapper.updateById(execution);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "执行队列已满，请稍后重试");
        }
        return executionId;
    }

    private void runAsync(Long executionId, List<GraphNode> nodes, List<GraphEdge> edges,
                          Map<String, Object> input) {
        sseRegistry.send(executionId, "execution_started",
                Map.of("executionId", executionId, "totalNodes", nodes.size()));
        SseExecutionListener listener = new SseExecutionListener(executionId, sseRegistry);
        ExecutionReport report;
        try {
            report = executionEngine.execute(nodes, edges, input, listener);
        } catch (CycleException e) {
            report = failedReport(e.getMessage());
        } catch (Exception e) {
            log.error("执行引擎异常 executionId={}", executionId, e);
            report = failedReport("执行引擎异常: " + e.getMessage());
        }
        try {
            persistenceService.persist(executionId, report);
        } catch (Exception e) {
            log.error("执行结果落库失败 executionId={}", executionId, e);
        }
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

    private ExecutionReport failedReport(String errorMessage) {
        ExecutionReport report = new ExecutionReport();
        report.setStatus(ExecutionReport.STATUS_FAILED);
        report.setErrorMessage(errorMessage == null ? "unknown" : errorMessage);
        return report;
    }

    /** input 必填校验：fields 里无 default 的 key 视为必填（契约 input.fields 无 required 标志）。 */
    private Map<String, Object> mergeInputWithDefaults(WorkflowNode inputNode, Map<String, Object> input) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (input != null) {
            merged.putAll(input);
        }
        if (inputNode == null) {
            return merged;
        }
        JsonNode fields = readTree(inputNode.getConfig()).path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                String key = field.path("key").asText(null);
                if (key == null || merged.containsKey(key)) {
                    continue;
                }
                JsonNode defaultValue = field.get("default");
                if (defaultValue != null && !defaultValue.isNull()) {
                    merged.put(key, objectMapper.convertValue(defaultValue, Object.class));
                } else {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "缺少必填字段: " + key);
                }
            }
        }
        return merged;
    }

    private WorkflowNode findInputNode(List<WorkflowNode> nodes) {
        return nodes.stream()
                .filter(n -> "input".equals(n.getType()))
                .findFirst()
                .orElse(null);
    }

    private List<GraphNode> toGraphNodes(List<WorkflowNode> nodes) {
        return nodes.stream()
                .map(n -> new GraphNode(n.getNodeKey(), n.getType(), n.getName(),
                        toConfigMap(n.getConfig())))
                .toList();
    }

    private Map<String, Object> toConfigMap(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.convertValue(readTree(configJson),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_GRAPH, "节点 config 解析失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    // ---------------- 查询 ----------------

    /**
     * SSE 订阅：执行已结束则补发终态事件后关闭；running 则等待执行线程推送。
     * 已知边界：DB 判定 running 与执行线程 complete 之间存在毫秒级竞态，
     * 极端情况下订阅方会拿到已关闭的连接（结果仍可查详情，前端需兜底轮询）。
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(Long id) {
        Execution execution = loadOwned(id);
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = sseRegistry.getOrCreate(id);
        if (!"running".equals(execution.getStatus())) {
            if ("success".equals(execution.getStatus())) {
                sseRegistry.send(id, "execution_completed", Map.of(
                        "output", readTreeOrEmpty(execution.getOutput()),
                        "totalTokens", execution.getTotalTokens() == null ? 0 : execution.getTotalTokens(),
                        "durationMs", execution.getDurationMs() == null ? 0L : execution.getDurationMs()));
            } else {
                sseRegistry.send(id, "execution_failed", Map.of(
                        "error", execution.getErrorMessage() == null ? "unknown" : execution.getErrorMessage()));
            }
            sseRegistry.complete(id);
        }
        return emitter;
    }

    private JsonNode readTreeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    public ExecutionVO get(Long id) {
        Execution execution = loadOwned(id);
        Map<Long, String> names = workflowNames(List.of(execution.getWorkflowId()));
        return toVO(execution, names);
    }

    public Map<String, Object> logs(Long id) {
        Execution execution = loadOwned(id);
        List<NodeLogVO> items = logMapper
                .selectList(new LambdaQueryWrapper<ExecutionNodeLog>()
                        .eq(ExecutionNodeLog::getExecutionId, id)
                        .orderByAsc(ExecutionNodeLog::getId))
                .stream()
                .map(this::toLogVO)
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    public PageVO<ExecutionVO> list(Long workflowId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size < 1 ? 10 : Math.min(size, 50);
        LambdaQueryWrapper<Execution> wrapper = new LambdaQueryWrapper<Execution>()
                .eq(Execution::getUserId, UserContext.getUserId())
                .orderByDesc(Execution::getStartedAt)
                .orderByDesc(Execution::getId);
        if (workflowId != null) {
            wrapper.eq(Execution::getWorkflowId, workflowId);
        }
        Page<Execution> result = executionMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<ExecutionVO> items = result.getRecords().stream()
                .map(e -> toVO(e, null))
                .toList();
        fillWorkflowNames(items);
        return new PageVO<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private Execution loadOwned(Long id) {
        Execution execution = executionMapper.selectById(id);
        if (execution == null) {
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND);
        }
        if (!execution.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看他人的执行记录");
        }
        return execution;
    }

    private void fillWorkflowNames(List<ExecutionVO> items) {
        Map<Long, String> names = workflowNames(items.stream()
                .map(ExecutionVO::getWorkflowId)
                .distinct()
                .toList());
        items.forEach(vo -> vo.setWorkflowName(names.get(vo.getWorkflowId())));
    }

    private Map<Long, String> workflowNames(List<Long> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Map.of();
        }
        return workflowMapper.selectBatchIds(workflowIds).stream()
                .collect(Collectors.toMap(Workflow::getId,
                        w -> w.getName() == null ? "" : w.getName()));
    }

    private ExecutionVO toVO(Execution execution, Map<Long, String> presetNames) {
        ExecutionVO vo = new ExecutionVO();
        vo.setId(execution.getId());
        vo.setWorkflowId(execution.getWorkflowId());
        if (presetNames != null) {
            // 工作流已删除时名称缺失（执行历史保留），前端自行兜底展示
            vo.setWorkflowName(presetNames.getOrDefault(execution.getWorkflowId(), null));
        }
        vo.setStatus(execution.getStatus());
        vo.setInput(parseJson(execution.getInput()));
        vo.setOutput(parseJson(execution.getOutput()));
        vo.setTotalTokens(execution.getTotalTokens());
        vo.setTotalCost(execution.getTotalCost());
        vo.setDurationMs(execution.getDurationMs());
        vo.setErrorMessage(execution.getErrorMessage());
        vo.setStartedAt(execution.getStartedAt());
        vo.setFinishedAt(execution.getFinishedAt());
        return vo;
    }

    private NodeLogVO toLogVO(ExecutionNodeLog row) {
        NodeLogVO vo = new NodeLogVO();
        vo.setNodeKey(row.getNodeKey());
        vo.setNodeName(row.getNodeName());
        vo.setNodeType(row.getNodeType());
        vo.setStatus(row.getStatus());
        vo.setInput(parseJson(row.getInput()));
        vo.setOutput(parseJson(row.getOutput()));
        vo.setTokens(row.getTokens());
        vo.setDurationMs(row.getDurationMs());
        vo.setErrorMessage(row.getErrorMessage());
        return vo;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
