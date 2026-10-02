package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.UserContext;
import com.flowmind.dto.publish.PublishVO;
import com.flowmind.dto.publish.PublicRunRequest;
import com.flowmind.entity.Execution;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowApiKey;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.controller.PublicRunController;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowApiKeyMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API 发布全链路（阶梯 8）：发布幂等（key 一次性）、吊销 401、key 限流、
 * 越权查询 404、run 走 submit（triggerType=api）。
 */
@SpringBootTest
class PublishFlowTest {

    @Autowired
    private PublishService publishService;
    @Autowired
    private PublicRunController publicRunController;
    @Autowired
    private WorkflowApiKeyMapper apiKeyMapper;
    @Autowired
    private WorkflowMapper workflowMapper;
    @Autowired
    private WorkflowNodeMapper nodeMapper;
    @Autowired
    private WorkflowEdgeMapper edgeMapper;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long userId;
    private Long workflowId;
    private Long otherWorkflowId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("pubtest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("pub@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");

        workflowId = createWorkflow("pub-test-wf");
        saveSimpleGraph(workflowId);

        otherWorkflowId = createWorkflow("pub-other-wf");
        saveSimpleGraph(otherWorkflowId);
    }

    @AfterEach
    void tearDown() {
        UserContext.set(userId, "user");
        Long[] ids = {workflowId, otherWorkflowId};
        for (Long wfId : ids) {
            apiKeyMapper.delete(new LambdaQueryWrapper<WorkflowApiKey>()
                    .eq(WorkflowApiKey::getWorkflowId, wfId));
            executionMapper.delete(new LambdaQueryWrapper<Execution>()
                    .eq(Execution::getWorkflowId, wfId));
            nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, wfId));
            edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, wfId));
            workflowMapper.deleteById(wfId);
        }
        userMapper.deleteById(userId);
        UserContext.clear();
    }

    private Long createWorkflow(String name) {
        Workflow wf = new Workflow();
        wf.setUserId(userId);
        wf.setName(name);
        workflowMapper.insert(wf);
        return wf.getId();
    }

    private void saveSimpleGraph(Long wfId) {
        WorkflowNode input = new WorkflowNode();
        input.setWorkflowId(wfId);
        input.setNodeKey("input_1");
        input.setType("input");
        input.setName("input");
        input.setConfig("{\"fields\":[{\"key\":\"city\",\"label\":\"city\",\"type\":\"text\"}]}");
        nodeMapper.insert(input);
        WorkflowNode http = new WorkflowNode();
        http.setWorkflowId(wfId);
        http.setNodeKey("http_1");
        http.setType("http");
        http.setName("weather");
        http.setConfig("{\"tool_id\":1,\"params\":{\"city\":\"{{input.city}}\"},\"output_key\":\"data\"}");
        nodeMapper.insert(http);
        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflowId(wfId);
        edge.setSourceNodeKey("input_1");
        edge.setTargetNodeKey("http_1");
        edgeMapper.insert(edge);
    }

    private PublicRunRequest runRequest() {
        PublicRunRequest req = new PublicRunRequest();
        req.setInput(Map.of("city", "Nanchang"));
        return req;
    }

    @Test
    void publishIdempotentKeyShownOnlyOnce() {
        PublishVO first = publishService.publish(workflowId);
        assertTrue(first.isPublished());
        assertEquals(64, first.getApiKey().length(), "32 字节 hex = 64 字符");
        assertTrue(first.getApiKey().matches("[0-9a-f]{64}"));

        // 二次发布：不重发 key（幂等）
        PublishVO second = publishService.publish(workflowId);
        assertTrue(second.isPublished());
        assertNull(second.getApiKey(), "已发布不重暴露 key");

        // GET 也不含 key
        assertNull(publishService.get(workflowId).getApiKey());
        assertTrue(publishService.get(workflowId).isPublished());

        // 未发布工作流
        assertEquals(false, publishService.get(otherWorkflowId).isPublished());
    }

    @Test
    void revokeThenKeyInvalid() {
        String apiKey = publishService.publish(workflowId).getApiKey();
        publishService.revoke(workflowId);
        // 幂等
        publishService.revoke(workflowId);

        try {
            publicRunController.run(apiKey, runRequest());
            throw new AssertionError("吊销后应 401");
        } catch (BusinessException e) {
            assertEquals("INVALID_API_KEY", e.getErrorCode().getCode());
            assertEquals(401, e.getErrorCode().getStatus());
        }
    }

    @Test
    void runGoesThroughSubmitWithApiTriggerType() {
        String apiKey = publishService.publish(workflowId).getApiKey();
        var response = publicRunController.run(apiKey, runRequest());
        assertEquals(202, response.getStatusCode().value());
        Long executionId = response.getBody().get("executionId");

        Execution execution = executionMapper.selectById(executionId);
        assertEquals("api", execution.getTriggerType(), "API 调用的执行应标记 api");
        assertEquals(userId, execution.getUserId(), "执行归属 key 对应工作流的 owner");

        await().atMost(java.time.Duration.ofSeconds(30))
                .until(() -> !"running".equals(executionMapper.selectById(executionId).getStatus())
                        && !"queued".equals(executionMapper.selectById(executionId).getStatus()));

        // 结果查询：正常 key
        Map<String, Object> result = publicRunController.result(apiKey, executionId);
        assertEquals("success", result.get("status"));
        assertTrue(result.containsKey("output"), "success 后带 output");
    }

    @Test
    void crossKeyQueryRejected() {
        String keyA = publishService.publish(workflowId).getApiKey();
        String keyB = publishService.publish(otherWorkflowId).getApiKey();
        assertNotEquals(keyA, keyB);

        // key B 触发的执行，用 key A 查 → 404（横向越权防护）
        Long executionId = publicRunController.run(keyB, runRequest()).getBody().get("executionId");
        try {
            publicRunController.result(keyA, executionId);
            throw new AssertionError("越权查询应 404");
        } catch (BusinessException e) {
            assertEquals("EXECUTION_NOT_FOUND", e.getErrorCode().getCode());
        }

        // 不存在的执行
        try {
            publicRunController.result(keyA, 99999999L);
            throw new AssertionError("应 404");
        } catch (BusinessException e) {
            assertEquals("EXECUTION_NOT_FOUND", e.getErrorCode().getCode());
        }
    }

    @Test
    void invalidKeyRejected() {
        try {
            publicRunController.run("deadbeef".repeat(8), runRequest());
            throw new AssertionError("坏 key 应 401");
        } catch (BusinessException e) {
            assertEquals("INVALID_API_KEY", e.getErrorCode().getCode());
        }
    }

    @Test
    void apiKeyRateLimit() {
        // 同工作流连发：第 1 次受理，2-10 撞 409（业务拒绝但消耗限流计数），11-12 被 429 闸门拦截
        String apiKey = publishService.publish(workflowId).getApiKey();
        String rlKey = "rl:api:" + apiKey;
        redisTemplate.delete(rlKey);
        try {
            int rateLimited = 0;
            int accepted = 0;
            for (int i = 0; i < 12; i++) {
                try {
                    publicRunController.run(apiKey, runRequest());
                    accepted++;
                } catch (BusinessException e) {
                    if ("API_RATE_LIMITED".equals(e.getErrorCode().getCode())) {
                        rateLimited++;
                    }
                }
            }
            assertTrue(accepted >= 1, "首次应受理");
            assertTrue(rateLimited >= 2, "第 11 次起应 429（实际 " + rateLimited + "）");
        } finally {
            redisTemplate.delete(rlKey);
        }
    }
}
