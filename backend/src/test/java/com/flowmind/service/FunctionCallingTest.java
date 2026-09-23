package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶梯 4 Function Calling 全链路（真实 DeepSeek + 真实 weather_query 工具）：
 * 多轮工具调用、节点日志 tool_calls、max_tool_rounds 截断。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = "sk-.+")
class FunctionCallingTest {

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
    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private Long workflowId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("fctest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("fctest@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");

        Workflow workflow = new Workflow();
        workflow.setUserId(userId);
        workflow.setName("FC 测试");
        workflow.setDescription("");
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

    private void saveLlmGraph(String llmConfig) {
        WorkflowNode input = new WorkflowNode();
        input.setWorkflowId(workflowId);
        input.setNodeKey("input_1");
        input.setType("input");
        input.setName("输入");
        input.setConfig("{\"fields\":[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\"}]}");
        nodeMapper.insert(input);

        WorkflowNode llm = new WorkflowNode();
        llm.setWorkflowId(workflowId);
        llm.setNodeKey("llm_1");
        llm.setType("llm");
        llm.setName("FC 节点");
        llm.setConfig(llmConfig);
        nodeMapper.insert(llm);

        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflowId(workflowId);
        edge.setSourceNodeKey("input_1");
        edge.setTargetNodeKey("llm_1");
        edgeMapper.insert(edge);
    }

    private Execution awaitDone(Long executionId) {
        await().atMost(java.time.Duration.ofSeconds(90))
                .pollInterval(java.time.Duration.ofMillis(500))
                .until(() -> !"running".equals(executionMapper.selectById(executionId).getStatus()));
        return executionMapper.selectById(executionId);
    }

    private JsonNode llmOutput(Execution execution) throws Exception {
        return objectMapper.readTree(execution.getOutput()).path("llm_1");
    }

    @Test
    void weatherToolMultiRound_realDeepSeek() throws Exception {
        saveLlmGraph("{\"model\":\"deepseek-flash\",\"tools\":[1],"
                + "\"prompt\":\"请先用工具查询{{input.city}}的实时天气，然后基于工具返回的真实数据用一句话给出穿衣建议\","
                + "\"temperature\":0.3,\"output_key\":\"text\"}");

        Long executionId = executionService.submit(workflowId, Map.of("city", "Nanchang"));
        Execution execution = awaitDone(executionId);

        assertEquals("success", execution.getStatus(), "执行应成功: " + execution.getErrorMessage());
        JsonNode llm = llmOutput(execution);
        String text = llm.path("text").asText("");
        assertTrue(!text.isBlank() && !text.contains("截断"), "应有最终文本: " + text);

        JsonNode toolCalls = llm.path("tool_calls");
        assertTrue(toolCalls.isArray() && toolCalls.size() >= 1, "tool_calls 日志应至少 1 项");
        assertEquals("weather_query", toolCalls.get(0).path("toolName").asText());
        assertEquals(true, toolCalls.get(0).path("ok").asBoolean());
        assertTrue(toolCalls.get(0).path("durationMs").asLong(0) >= 0);
        assertEquals("Nanchang", toolCalls.get(0).path("arguments").path("city").asText(),
                "模型应把城市参数传给工具");
        assertTrue(execution.getTotalTokens() > 0, "多轮 usage 应求和");

        List<ExecutionNodeLog> logs = logMapper.selectList(
                new LambdaQueryWrapper<ExecutionNodeLog>()
                        .eq(ExecutionNodeLog::getExecutionId, executionId)
                        .orderByAsc(ExecutionNodeLog::getId));
        assertEquals(2, logs.size());
        assertEquals("success", logs.get(1).getStatus());
        JsonNode logOutput = objectMapper.readTree(logs.get(1).getOutput());
        assertTrue(logOutput.path("tool_calls").isArray(), "节点日志 output 应含 tool_calls");
    }

    // 截断场景在 LlmNodeExecutorTruncationTest 用 MockWebServer 确定性验证：
    // 真实 v4 推理模型会识破"永远调用工具"的指令直接给出答案，无法稳定触发 max_tool_rounds。
}
