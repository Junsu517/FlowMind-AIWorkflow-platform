package com.flowmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.model.ExecutionReport;
import com.flowmind.engine.model.NodeResult;
import com.flowmind.entity.Execution;
import com.flowmind.entity.ExecutionNodeLog;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.ExecutionNodeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 执行结果落库（独立类保证 @Transactional 经代理生效）：
 * execution 行更新 + 逐节点 execution_node_logs 插入，同一事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionPersistenceService {

    /**
     * DeepSeek v4-flash 单价（2026-09，来源 https://api-docs.deepseek.com/quick_start/pricing）：
     * 输入 $0.14/百万 tokens、输出 $0.28/百万 tokens。
     * 引擎只汇总 totalTokens 不分输入输出，此处取两者算术中值 $0.21/百万 估算。
     */
    private static final BigDecimal COST_PER_1M_TOKENS = new BigDecimal("0.21");

    private static final int ERROR_MESSAGE_MAX = 500;

    private final ExecutionMapper executionMapper;
    private final ExecutionNodeLogMapper logMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public void persist(Long executionId, ExecutionReport report) {
        Execution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            log.warn("执行记录不存在，结果丢弃: executionId={}", executionId);
            return;
        }
        execution.setStatus(report.getStatus());
        execution.setOutput(writeJson(report.getOutput()));
        execution.setTotalTokens(report.getTotalTokens());
        execution.setTotalCost(BigDecimal.valueOf(report.getTotalTokens())
                .multiply(COST_PER_1M_TOKENS)
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP));
        execution.setDurationMs(report.getDurationMs());
        execution.setErrorMessage(truncate(report.getErrorMessage()));
        execution.setFinishedAt(LocalDateTime.now());
        executionMapper.updateById(execution);

        for (NodeResult nodeResult : report.getNodeResults()) {
            ExecutionNodeLog row = new ExecutionNodeLog();
            row.setExecutionId(executionId);
            row.setNodeKey(nodeResult.getNodeKey());
            row.setNodeName(nodeResult.getNodeName() == null ? "" : nodeResult.getNodeName());
            row.setNodeType(nodeResult.getNodeType());
            row.setStatus(nodeResult.getStatus());
            row.setInput(writeJson(nodeResult.getInput()));
            row.setOutput(writeJson(nodeResult.getOutput()));
            row.setTokens(nodeResult.getTokens());
            row.setDurationMs(nodeResult.getDurationMs());
            row.setErrorMessage(truncate(nodeResult.getErrorMessage()));
            logMapper.insert(row);
        }
    }

    /** 失败信息截断到 500 字符，防脏数据（任务卡要求）。 */
    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MESSAGE_MAX ? message : message.substring(0, ERROR_MESSAGE_MAX);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败，降级为 toString: {}", e.getMessage());
            return String.valueOf(value);
        }
    }
}
