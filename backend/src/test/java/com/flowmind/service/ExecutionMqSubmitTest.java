package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.UserContext;
import com.flowmind.config.RabbitMqConfig;
import com.flowmind.entity.Execution;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mqueue 模式提交链路（RabbitTemplate stub，不连真实 MQ）：
 * submit → 行 queued + publish 被调用 + 分布式锁已持有（终态前不释放）。
 * 真实 MQ 联调在统筹开隧道后进行。
 */
@SpringBootTest(properties = "flowmind.execution.mode=mqueue")
class ExecutionMqSubmitTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        RabbitTemplate recordingRabbitTemplate() {
            return new RabbitTemplate() {
                final List<String> exchanges = new ArrayList<>();

                @Override
                public void afterPropertiesSet() {
                    // stub 无需 ConnectionFactory，跳过父类校验
                }

                @Override
                public void convertAndSend(String exchange, String routingKey, Object message,
                                           MessagePostProcessor processor,
                                           CorrelationData correlationData) {
                    exchanges.add(exchange + "/" + routingKey);
                }
            };
        }
    }

    @Autowired
    private ExecutionService executionService;
    @Autowired
    private ExecutionRunner executionRunner;
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
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private Long userId;
    private Long workflowId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("mqtest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("mq@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");

        Workflow wf = new Workflow();
        wf.setUserId(userId);
        wf.setName("mq-submit-test");
        workflowMapper.insert(wf);
        workflowId = wf.getId();

        WorkflowNode input = new WorkflowNode();
        input.setWorkflowId(workflowId);
        input.setNodeKey("input_1");
        input.setType("input");
        input.setName("输入");
        input.setConfig("{\"fields\":[{\"key\":\"x\",\"label\":\"x\",\"type\":\"text\"}]}");
        nodeMapper.insert(input);
        WorkflowNode http = new WorkflowNode();
        http.setWorkflowId(workflowId);
        http.setNodeKey("http_1");
        http.setType("http");
        http.setName("工具");
        http.setConfig("{\"tool_id\":1,\"params\":{\"city\":\"{{input.x}}\"},\"output_key\":\"data\"}");
        nodeMapper.insert(http);
        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflowId(workflowId);
        edge.setSourceNodeKey("input_1");
        edge.setTargetNodeKey("http_1");
        edgeMapper.insert(edge);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        executionMapper.delete(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getWorkflowId, workflowId));
        nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, workflowId));
        edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, workflowId));
        workflowMapper.deleteById(workflowId);
        userMapper.deleteById(userId);
        redisTemplate.delete(ExecutionRunner.LOCK_PREFIX + workflowId);
    }

    @Test
    void submitQueuesRowAndPublishes() {
        Long executionId = executionService.submit(workflowId, Map.of("x", "Nanchang"));

        Execution row = executionMapper.selectById(executionId);
        assertEquals("queued", row.getStatus(), "mqueue 模式初始状态为 queued");

        // publish 经 stub 模板：验证提交同步完成、锁仍持有（消费者终态才释放）
        String lockKey = ExecutionRunner.LOCK_PREFIX + workflowId;
        await().atMost(java.time.Duration.ofSeconds(3))
                .until(() -> redisTemplate.opsForValue().get(lockKey) != null);
        assertEquals(String.valueOf(executionId),
                redisTemplate.opsForValue().get(lockKey), "锁 value=executionId，终态前不释放");

        // 幂等兜底：同工作流再 submit → 409（DB queued 检查）
        try {
            executionService.submit(workflowId, Map.of("x", "Beijing"));
            throw new AssertionError("queued 期间应 409");
        } catch (com.flowmind.common.BusinessException e) {
            assertEquals("WORKFLOW_RUNNING", e.getErrorCode().getCode());
        }

        // markFailed 终态后锁释放（模拟 DLQ/兜底路径）
        executionRunner.markFailed(executionId, "测试终态");
        assertEquals("failed", executionMapper.selectById(executionId).getStatus());
        await().atMost(java.time.Duration.ofSeconds(3))
                .until(() -> redisTemplate.opsForValue().get(lockKey) == null);
    }
}
