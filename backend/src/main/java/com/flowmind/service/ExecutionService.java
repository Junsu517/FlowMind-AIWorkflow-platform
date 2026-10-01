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
import com.flowmind.entity.Execution;
import com.flowmind.entity.ExecutionNodeLog;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.ExecutionNodeLogMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 执行提交与查询（阶梯 6 改造）：
 * 提交 = Redis 分布式锁（主防线）→ 落 queued 行 → MQ publish（mqueue）或线程池（thread）→ 202。
 * 实际执行在 ExecutionRunner（两种模式共用），SSE/落库行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    /** execute 限流：每用户每分钟 10 次（契约 v1.3） */
    private static final int EXECUTE_RATE_LIMIT = 10;
    private static final int EXECUTE_RATE_WINDOW_SECONDS = 60;

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final ExecutionMapper executionMapper;
    private final ExecutionNodeLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.ThreadPoolExecutor executionThreadPool;
    private final RateLimitService rateLimitService;
    private final SseEmitterRegistry sseRegistry;
    private final DistributedLockService lockService;
    private final ExecutionQueueService queueService;
    private final ExecutionRunner executionRunner;

    @Value("${flowmind.execution.mode:thread}")
    private String executionMode;

    /**
     * 手动执行入口（阶梯 7 前的唯一入口）。
     */
    public Long submit(Long workflowId, Map<String, Object> input) {
        return submit(workflowId, input, "manual");
    }

    /**
     * 提交执行：立即返回 executionId（202）。
     * 锁获取失败或 DB 状态检查命中 → 409；消息队列不可达 → 500（行回置 failed）。
     * triggerType：manual（手动）/ scheduled（定时），仅落库标记，执行逻辑与 trigger 无关。
     */
    public Long submit(Long workflowId, Map<String, Object> input, String triggerType) {
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
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_GRAPH, "工作流未保存图，无法执行");
        }
        Map<String, Object> mergedInput = mergeInputWithDefaults(findInputNode(nodes), input);

        // DB 状态兜底检查（锁是主防线，这里挡掉大部分快路径）
        Long active = executionMapper.selectCount(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getWorkflowId, workflowId)
                .in(Execution::getStatus, "queued", "running"));
        if (active != null && active > 0) {
            throw new BusinessException(ErrorCode.WORKFLOW_RUNNING, "该工作流已有执行在运行，请稍后再试");
        }

        Execution execution = new Execution();
        execution.setWorkflowId(workflowId);
        execution.setUserId(userId);
        execution.setStatus("queued");
        execution.setTriggerType(triggerType);
        execution.setInput(writeJson(mergedInput));
        execution.setStartedAt(java.time.LocalDateTime.now());
        executionMapper.insert(execution);

        String lockKey = ExecutionRunner.LOCK_PREFIX + workflowId;
        String lockValue = String.valueOf(execution.getId());
        if (!lockService.acquire(lockKey, lockValue, ExecutionRunner.LOCK_TTL_SECONDS)) {
            executionMapper.deleteById(execution.getId());
            throw new BusinessException(ErrorCode.WORKFLOW_RUNNING, "该工作流已有执行在运行，请稍后再试");
        }

        try {
            if ("mqueue".equalsIgnoreCase(executionMode)) {
                queueService.publish(execution.getId());
            } else {
                Long executionId = execution.getId();
                executionThreadPool.execute(() -> {
                    try {
                        executionRunner.runByExecutionId(executionId);
                    } catch (Exception ex) {
                        // 线程池模式无重试通道：系统异常直接落终态（Runner 已回置 queued，这里翻 failed）
                        log.error("线程池执行异常 executionId={}", executionId, ex);
                        executionRunner.markFailed(executionId, "执行系统异常: " + ex.getMessage());
                    }
                });
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            executionRunner.markFailed(execution.getId(), "执行队列已满，请稍后重试");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "执行队列已满，请稍后重试");
        } catch (IllegalStateException e) {
            // publish 失败（MQ 不可达）：行回置 failed 并释放锁
            executionRunner.markFailed(execution.getId(), "任务提交失败: " + e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务提交失败: " + e.getMessage());
        }
        return execution.getId();
    }

    // ---------------- 查询 ----------------

    /**
     * SSE 订阅：执行已结束则补发终态事件后关闭；queued/running 则等待推送。
     * 已知边界：DB 判定与执行线程 complete 之间存在毫秒级竞态，
     * 极端情况下订阅方会拿到已关闭的连接（结果仍可查详情，前端需兜底轮询）。
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(Long id) {
        Execution execution = loadOwned(id);
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = sseRegistry.getOrCreate(id);
        if (isTerminal(execution.getStatus())) {
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

    private boolean isTerminal(String status) {
        return "success".equals(status) || "failed".equals(status) || "cancelled".equals(status);
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
        vo.setTriggerType(execution.getTriggerType());
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

    // ---------------- 提交前校验 ----------------

    /** input 必填校验：fields 里无 default 的 key 视为必填（契约 v1.3）。 */
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
