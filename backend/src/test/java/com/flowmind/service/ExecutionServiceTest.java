package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.UserContext;
import com.flowmind.entity.Execution;
import com.flowmind.entity.ExecutionNodeLog;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.ExecutionNodeLogMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 DB + 真实外部 API 的执行链路测试。
 * LLM 用例需要环境变量 DEEPSEEK_API_KEY；http 用例走 tools 表预置的 weather_query（wttr.in）。
 */
@SpringBootTest
class ExecutionServiceTest {

    @Autowired
    private ExecutionService executionService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkflowMapper workflowMapper;
    @Autowired
    private WorkflowNodeMapper nodeMapper;
    @Autowired
    private WorkflowEdgeMapper edgeMapper;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private ExecutionNodeLogMapper logMapper;

    private Long userId;
    private Long workflowId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("gtest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("gtest@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");

        Workflow workflow = new Workflow();
        workflow.setUserId(userId);
        workflow.setName("执行链路测试");
        workflow.setDescription("JUnit 自动创建");
        workflowMapper.insert(workflow);
        workflowId = workflow.getId();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        List<Long> executionIds = executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                        .eq(Execution::getWorkflowId, workflowId))
                .stream().map(Execution::getId).toList();
        if (!executionIds.isEmpty()) {
            logMapper.delete(new LambdaQueryWrapper<ExecutionNodeLog>()
                    .in(ExecutionNodeLog::getExecutionId, executionIds));
            executionMapper.deleteByIds(executionIds);
        }
        nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, workflowId));
        edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, workflowId));
        workflowMapper.deleteById(workflowId);
        userMapper.deleteById(userId);
    }

    private void saveGraph(String inputConfig, String nodeKey, String type, String nodeConfig) {
        WorkflowNode input = new WorkflowNode();
        input.setWorkflowId(workflowId);
        input.setNodeKey("input_1");
        input.setType("input");
        input.setName("输入");
        input.setConfig(inputConfig);
        nodeMapper.insert(input);

        WorkflowNode node = new WorkflowNode();
        node.setWorkflowId(workflowId);
        node.setNodeKey(nodeKey);
        node.setType(type);
        node.setName("被测节点");
        node.setConfig(nodeConfig);
        nodeMapper.insert(node);

        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflowId(workflowId);
        edge.setSourceNodeKey("input_1");
        edge.setTargetNodeKey(nodeKey);
        edgeMapper.insert(edge);
    }

    private Execution awaitDone(Long executionId) {
        await().atMost(java.time.Duration.ofSeconds(30))
                .pollInterval(java.time.Duration.ofMillis(500))
                .until(() -> !"running".equals(
                        executionMapper.selectById(executionId).getStatus()));
        return executionMapper.selectById(executionId);
    }

    @Test
    void inputToHttp_weatherQuery_realCall() {
        saveGraph(
                "{\"fields\":[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\"}]}",
                "http_1", "http",
                "{\"tool_id\":1,\"params\":{\"city\":\"{{input.city}}\"},\"output_key\":\"data\"}");

        Long executionId = executionService.submit(workflowId, Map.of("city", "Nanchang"));
        Execution execution = awaitDone(executionId);

        assertEquals("success", execution.getStatus(), "执行应成功: " + execution.getErrorMessage());
        assertNotNull(execution.getOutput());
        assertTrue(execution.getOutput().contains("current_condition"), "weather_query 应返回天气 JSON");

        List<ExecutionNodeLog> logs = logMapper.selectList(
                new LambdaQueryWrapper<ExecutionNodeLog>()
                        .eq(ExecutionNodeLog::getExecutionId, executionId)
                        .orderByAsc(ExecutionNodeLog::getId));
        assertEquals(2, logs.size());
        assertEquals("input_1", logs.get(0).getNodeKey());
        assertEquals("success", logs.get(0).getStatus());
        assertEquals("http_1", logs.get(1).getNodeKey());
        assertEquals("success", logs.get(1).getStatus());
        assertTrue(logs.get(1).getDurationMs() >= 0);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = "sk-.+")
    void inputToLlm_realDeepSeek() {
        saveGraph(
                "{\"fields\":[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\",\"default\":\"南昌\"}]}",
                "llm_1", "llm",
                "{\"model\":\"deepseek-v4-flash\",\"prompt\":\"用一句话介绍{{input.city}}的天气特点\",\"temperature\":0.1,\"output_key\":\"text\"}");

        Long executionId = executionService.submit(workflowId, Map.of("city", "南昌"));
        Execution execution = awaitDone(executionId);

        assertEquals("success", execution.getStatus(), "执行应成功: " + execution.getErrorMessage());
        assertTrue(execution.getOutput().contains("text"), "llm 输出应有 text 字段");
        assertTrue(execution.getTotalTokens() > 0, "真实调用应统计到 tokens");
        assertNotNull(execution.getTotalCost());
        assertTrue(execution.getTotalCost().doubleValue() > 0);

        List<ExecutionNodeLog> logs = logMapper.selectList(
                new LambdaQueryWrapper<ExecutionNodeLog>()
                        .eq(ExecutionNodeLog::getExecutionId, executionId)
                        .orderByAsc(ExecutionNodeLog::getId));
        assertEquals(2, logs.size());
        assertEquals("llm", logs.get(1).getNodeType());
        assertEquals("success", logs.get(1).getStatus());
        assertTrue(logs.get(1).getTokens() > 0);
        assertTrue(logs.get(1).getDurationMs() > 0);
    }

    @Test
    void rejectWhenWorkflowRunning_409() {
        Execution running = new Execution();
        running.setWorkflowId(workflowId);
        running.setUserId(userId);
        running.setStatus("running");
        running.setStartedAt(java.time.LocalDateTime.now());
        executionMapper.insert(running);

        saveGraph(
                "{\"fields\":[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\"}]}",
                "http_1", "http",
                "{\"tool_id\":1,\"params\":{\"city\":\"{{input.city}}\"},\"output_key\":\"data\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executionService.submit(workflowId, Map.of("city", "Beijing")));
        assertEquals("WORKFLOW_RUNNING", ex.getErrorCode().getCode());
    }

    @Test
    void rejectMissingRequiredInput_400() {
        saveGraph(
                "{\"fields\":[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\"}]}",
                "http_1", "http",
                "{\"tool_id\":1,\"params\":{\"city\":\"{{input.city}}\"},\"output_key\":\"data\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executionService.submit(workflowId, Map.of()));
        assertEquals("INVALID_INPUT", ex.getErrorCode().getCode());
    }

    @Test
    void rejectWhenGraphMissing_400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executionService.submit(workflowId, Map.of("city", "Beijing")));
        assertEquals("INVALID_GRAPH", ex.getErrorCode().getCode());
    }
}
