package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.dto.auth.AuthResponse;
import com.flowmind.dto.auth.RegisterRequest;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册即送示例工作流：走真实 register 全流程，断言 1 工作流 + 3 节点 + 2 边 + config 内容。 */
@SpringBootTest
class DemoWorkflowServiceTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkflowMapper workflowMapper;
    @Autowired
    private WorkflowNodeMapper nodeMapper;
    @Autowired
    private WorkflowEdgeMapper edgeMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;

    @AfterEach
    void tearDown() {
        if (userId == null) {
            return;
        }
        List<Workflow> workflows = workflowMapper.selectList(
                new LambdaQueryWrapper<Workflow>().eq(Workflow::getUserId, userId));
        workflows.forEach(w -> {
            nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, w.getId()));
            edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, w.getId()));
            workflowMapper.deleteById(w.getId());
        });
        userMapper.deleteById(userId);
    }

    @Test
    void registerCreatesDemoWorkflow() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("demo_" + System.currentTimeMillis());
        req.setPassword("12345678");
        req.setEmail("demo@x.com");
        AuthResponse response = authService.register(req);
        userId = response.getUser().getId();
        assertNotNull(response.getToken(), "注册响应结构不变，正常返回 token");

        List<Workflow> workflows = workflowMapper.selectList(
                new LambdaQueryWrapper<Workflow>().eq(Workflow::getUserId, userId));
        assertEquals(1, workflows.size());
        Workflow demo = workflows.get(0);
        assertEquals(DemoWorkflowService.DEMO_NAME, demo.getName());
        assertEquals(DemoWorkflowService.DEMO_DESCRIPTION, demo.getDescription());

        List<WorkflowNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, demo.getId())
                        .orderByAsc(WorkflowNode::getId));
        assertEquals(3, nodes.size());
        assertEquals("input_1", nodes.get(0).getNodeKey());
        assertEquals("input", nodes.get(0).getType());
        assertEquals(85D, nodes.get(0).getPositionX());
        assertEquals(112D, nodes.get(0).getPositionY());

        JsonNode httpConfig = objectMapper.readTree(nodes.get(1).getConfig());
        assertEquals(1, httpConfig.path("tool_id").asInt());
        JsonNode llmConfig = objectMapper.readTree(nodes.get(2).getConfig());
        assertEquals("deepseek-flash", llmConfig.path("model").asText());
        assertTrue(llmConfig.path("prompt").asText().contains("{{http_1.data}}"));

        List<WorkflowEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, demo.getId()));
        assertEquals(2, edges.size());
        assertTrue(edges.stream().allMatch(e -> e.getSourceHandle() == null));
    }

    @Test
    void duplicateRegisterStillRejected() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("demo_dup_" + System.currentTimeMillis());
        req.setPassword("12345678");
        req.setEmail("dup@x.com");
        AuthResponse first = authService.register(req);
        userId = first.getUser().getId();

        try {
            authService.register(req);
            throw new AssertionError("重复注册应抛 USERNAME_EXISTS");
        } catch (com.flowmind.common.BusinessException e) {
            assertEquals("USERNAME_EXISTS", e.getErrorCode().getCode());
        }
        List<Workflow> workflows = workflowMapper.selectList(
                new LambdaQueryWrapper<Workflow>().eq(Workflow::getUserId, userId));
        assertEquals(1, workflows.size(), "重复注册被拒，不应再建示例");
    }
}
