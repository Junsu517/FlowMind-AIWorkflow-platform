package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.BusinessException;
import com.flowmind.common.UserContext;
import com.flowmind.dto.schedule.ScheduleRequest;
import com.flowmind.dto.schedule.ScheduleVO;
import com.flowmind.entity.Execution;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowEdge;
import com.flowmind.entity.WorkflowNode;
import com.flowmind.entity.WorkflowSchedule;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowEdgeMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowNodeMapper;
import com.flowmind.mapper.WorkflowScheduleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 定时计划：cron 校验、动态注册/注销、更新替换、启动恢复、触发走 submit（triggerType=scheduled）、撞车 409。 */
@SpringBootTest
class ScheduleServiceTest {

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private Scheduler scheduler;
    @Autowired
    private WorkflowScheduleMapper scheduleMapper;
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

    private Long userId;
    private Long workflowId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("schedtest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("sched@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");

        Workflow wf = new Workflow();
        wf.setUserId(userId);
        wf.setName("sched-test-wf");
        workflowMapper.insert(wf);
        workflowId = wf.getId();

        // input(city 默认 Nanchang) → http(weather_query) 简单图，供触发真实执行
        WorkflowNode input = new WorkflowNode();
        input.setWorkflowId(workflowId);
        input.setNodeKey("input_1");
        input.setType("input");
        input.setName("input");
        input.setConfig("{\"fields\":[{\"key\":\"city\",\"label\":\"city\",\"type\":\"text\",\"default\":\"Nanchang\"}]}");
        nodeMapper.insert(input);
        WorkflowNode http = new WorkflowNode();
        http.setWorkflowId(workflowId);
        http.setNodeKey("http_1");
        http.setType("http");
        http.setName("weather");
        http.setConfig("{\"tool_id\":1,\"params\":{\"city\":\"{{input.city}}\"},\"output_key\":\"data\"}");
        nodeMapper.insert(http);
        WorkflowEdge edge = new WorkflowEdge();
        edge.setWorkflowId(workflowId);
        edge.setSourceNodeKey("input_1");
        edge.setTargetNodeKey("http_1");
        edgeMapper.insert(edge);
    }

    @AfterEach
    void tearDown() throws SchedulerException {
        UserContext.set(userId, "user");
        scheduleService.delete(workflowId);
        scheduleMapper.delete(new LambdaQueryWrapper<WorkflowSchedule>()
                .eq(WorkflowSchedule::getWorkflowId, workflowId));
        executionMapper.delete(new LambdaQueryWrapper<Execution>()
                .eq(Execution::getWorkflowId, workflowId));
        nodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, workflowId));
        edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdge>().eq(WorkflowEdge::getWorkflowId, workflowId));
        workflowMapper.deleteById(workflowId);
        userMapper.deleteById(userId);
        UserContext.clear();
    }

    private ScheduleRequest request(String cron, Boolean enabled) {
        ScheduleRequest req = new ScheduleRequest();
        req.setCron(cron);
        req.setEnabled(enabled);
        req.setInput(Map.of("city", "Beijing"));
        return req;
    }

    @Test
    void invalidCronRejected() {
        try {
            scheduleService.upsert(workflowId, request("not a cron", true));
            throw new AssertionError("应抛 SCHEDULE_INVALID");
        } catch (BusinessException e) {
            assertEquals("SCHEDULE_INVALID", e.getErrorCode().getCode());
        }
    }

    @Test
    void upsertRegistersAndReplaceUpdates() throws SchedulerException {
        scheduleService.upsert(workflowId, request("0 0 3 * * ?", true));
        assertTrue(scheduler.checkExists(ScheduleService.jobKey(workflowId)), "upsert 后 Job 应注册");

        // 更新 cron → 替换（仍只有一个注册，trigger 的 cron 已更新）
        ScheduleVO vo = scheduleService.upsert(workflowId, request("0 30 4 * * ?", true));
        assertEquals("0 30 4 * * ?", vo.getCron());
        assertTrue(vo.isConfigured());
        assertNull(vo.getLastRunAt());
        assertTrue(vo.getNextFireTime() != null, "启用计划应给出下次触发时间");

        // enabled=false → 注销但保留 DB 行
        ScheduleVO disabled = scheduleService.upsert(workflowId, request("0 30 4 * * ?", false));
        assertFalse(disabled.getEnabled());
        assertFalse(scheduler.checkExists(ScheduleService.jobKey(workflowId)), "停用后应注销");
    }

    @Test
    void deleteRemovesRowAndJob() throws SchedulerException {
        scheduleService.upsert(workflowId, request("0 0 5 * * ?", true));
        scheduleService.delete(workflowId);
        assertFalse(scheduler.checkExists(ScheduleService.jobKey(workflowId)));
        assertNull(scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowSchedule>()
                .eq(WorkflowSchedule::getWorkflowId, workflowId)));
        assertEquals(false, scheduleService.get(workflowId).isConfigured(), "删除后 GET 返回 configured=false");
    }

    @Test
    void unconfiguredGetReturnsNotConfigured() {
        ScheduleVO vo = scheduleService.get(workflowId);
        assertFalse(vo.isConfigured());
        assertNull(vo.getCron());
    }

    @Test
    void startupRecoveryReregistersFromDb() throws SchedulerException {
        scheduleService.upsert(workflowId, request("0 0 6 * * ?", true));
        // 模拟内存模式重启丢注册
        scheduler.deleteJob(ScheduleService.jobKey(workflowId));
        assertFalse(scheduler.checkExists(ScheduleService.jobKey(workflowId)));

        scheduleService.recoverSchedules();
        assertTrue(scheduler.checkExists(ScheduleService.jobKey(workflowId)), "恢复应重新注册");
    }

    @Test
    void fireScheduledGoesThroughSubmitWithTriggerType() {
        scheduleService.upsert(workflowId, request("0 0 7 * * ?", true));
        scheduleService.fireScheduled(workflowId);

        List<Execution> executions = executionMapper.selectList(
                new LambdaQueryWrapper<Execution>().eq(Execution::getWorkflowId, workflowId));
        assertEquals(1, executions.size());
        assertEquals("scheduled", executions.get(0).getTriggerType(), "定时触发的执行应标记 scheduled");
        assertEquals(userId, executions.get(0).getUserId(), "执行归属计划所属用户");

        // last_run_at 已更新
        WorkflowSchedule schedule = scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowSchedule>()
                .eq(WorkflowSchedule::getWorkflowId, workflowId));
        assertTrue(schedule.getLastRunAt() != null);

        // 手动执行 → manual；fireScheduled 的 finally 会清 ThreadLocal，这里恢复测试上下文
        UserContext.set(userId, "user");
        scheduleService.delete(workflowId);
        await().atMost(java.time.Duration.ofSeconds(30))
                .until(() -> executionMapper.selectList(new LambdaQueryWrapper<Execution>()
                                .eq(Execution::getWorkflowId, workflowId))
                        .stream().allMatch(e -> !"running".equals(e.getStatus())
                                && !"queued".equals(e.getStatus())));
    }

    @Test
    void fireScheduledBlockedByLockDoesNotUpdateLastRun() {
        scheduleService.upsert(workflowId, request("0 0 8 * * ?", true));
        // 造一个在途执行挡住 409
        Execution running = new Execution();
        running.setWorkflowId(workflowId);
        running.setUserId(userId);
        running.setStatus("running");
        running.setStartedAt(java.time.LocalDateTime.now());
        executionMapper.insert(running);
        try {
            scheduleService.fireScheduled(workflowId);
            List<Execution> executions = executionMapper.selectList(
                    new LambdaQueryWrapper<Execution>().eq(Execution::getWorkflowId, workflowId));
            assertEquals(1, executions.size(), "被锁挡住，不应产生新执行行");
            WorkflowSchedule schedule = scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowSchedule>()
                    .eq(WorkflowSchedule::getWorkflowId, workflowId));
            assertNull(schedule.getLastRunAt(), "被拒触发不更新 last_run_at");
        } finally {
            executionMapper.deleteById(running.getId());
        }
    }
}
