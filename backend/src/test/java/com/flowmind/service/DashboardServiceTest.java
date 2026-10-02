package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.UserContext;
import com.flowmind.dto.stats.DashboardVO;
import com.flowmind.entity.Execution;
import com.flowmind.entity.User;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowSchedule;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.UserMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowScheduleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仪表盘口径：成功率（进行中不计分母）、30 天补零、三键补零、Top 排序。 */
@SpringBootTest
class DashboardServiceTest {

    @Autowired
    private StatsService statsService;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private WorkflowMapper workflowMapper;
    @Autowired
    private WorkflowScheduleMapper scheduleMapper;
    @Autowired
    private UserMapper userMapper;

    private Long userId;
    private Long wf1;
    private Long wf2;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("dashtest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("dash@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");

        Workflow w1 = new Workflow();
        w1.setUserId(userId);
        w1.setName("dash-wf-1");
        workflowMapper.insert(w1);
        wf1 = w1.getId();
        Workflow w2 = new Workflow();
        w2.setUserId(userId);
        w2.setName("dash-wf-2");
        workflowMapper.insert(w2);
        wf2 = w2.getId();
    }

    @AfterEach
    void tearDown() {
        scheduleMapper.delete(new LambdaQueryWrapper<WorkflowSchedule>()
                .inSql(WorkflowSchedule::getWorkflowId, "SELECT id FROM workflows WHERE user_id = " + userId));
        executionMapper.delete(new LambdaQueryWrapper<Execution>().eq(Execution::getUserId, userId));
        workflowMapper.delete(new LambdaQueryWrapper<Workflow>().eq(Workflow::getUserId, userId));
        userMapper.deleteById(userId);
        UserContext.clear();
    }

    private Execution exec(Long workflowId, String status, String triggerType,
                           int tokens, BigDecimal cost, LocalDateTime startedAt) {
        Execution e = new Execution();
        e.setUserId(userId);
        e.setWorkflowId(workflowId);
        e.setStatus(status);
        e.setTriggerType(triggerType);
        e.setTotalTokens(tokens);
        e.setTotalCost(cost);
        e.setDurationMs(100L);
        e.setStartedAt(startedAt);
        return e;
    }

    @Test
    void summaryCountsAndSuccessRateExcludesInProgress() {
        LocalDateTime now = LocalDateTime.now();
        // 3 success + 1 failed + 2 running（不计分母）→ 成功率 0.75
        executionMapper.insert(exec(wf1, "success", "manual", 100, new BigDecimal("0.010000"), now));
        executionMapper.insert(exec(wf1, "success", "manual", 200, new BigDecimal("0.020000"), now));
        executionMapper.insert(exec(wf2, "success", "scheduled", 50, BigDecimal.ZERO, now));
        executionMapper.insert(exec(wf2, "failed", "api", 0, BigDecimal.ZERO, now));
        executionMapper.insert(exec(wf2, "running", "manual", 0, BigDecimal.ZERO, now));
        executionMapper.insert(exec(wf2, "queued", "manual", 0, BigDecimal.ZERO, now));

        DashboardVO vo = statsService.dashboard();

        assertEquals(6, vo.getSummary().getTotalExecutions(), "总数含进行中");
        assertEquals(0.75, vo.getSummary().getSuccessRate(), "3/(3+1)=0.75，进行中不计分母");
        assertEquals(350, vo.getSummary().getTotalTokens());
        assertEquals(0, new BigDecimal("0.030000").compareTo(vo.getSummary().getTotalCost()));
        assertEquals(2, vo.getSummary().getActiveWorkflows(), "用户工作流总数口径");
    }

    @Test
    void dailyFilledToThirtyDaysWithZeroes() {
        LocalDateTime now = LocalDateTime.now();
        executionMapper.insert(exec(wf1, "success", "manual", 80, BigDecimal.ZERO, now));
        executionMapper.insert(exec(wf1, "success", "manual", 20, BigDecimal.ZERO, now.minusDays(5)));
        executionMapper.insert(exec(wf1, "success", "manual", 999, BigDecimal.ZERO, now.minusDays(29)));
        // 31 天前：窗口外，不计
        executionMapper.insert(exec(wf1, "success", "manual", 888, BigDecimal.ZERO, now.minusDays(31)));

        List<DashboardVO.DailyItem> daily = statsService.dashboard().getDaily();
        assertEquals(30, daily.size(), "固定 30 项");
        assertEquals(java.time.LocalDate.now().toString(), daily.get(29).getDate(), "最后一项是今天");
        assertEquals(1, daily.get(29).getExecutions(), "今天 1 次执行");
        assertEquals(80, daily.get(29).getTokens(), "今天 tokens 合计");
        assertEquals(1, findDaily(daily, java.time.LocalDate.now().minusDays(5)).getExecutions());
        assertEquals(20, findDaily(daily, java.time.LocalDate.now().minusDays(5)).getTokens());
        assertEquals(1, findDaily(daily, java.time.LocalDate.now().minusDays(29)).getExecutions(),
                "30 天窗口边界（29 天前）计入");
        assertEquals(999, findDaily(daily, java.time.LocalDate.now().minusDays(29)).getTokens());
        long totalInWindow = daily.stream().mapToLong(DashboardVO.DailyItem::getExecutions).sum();
        assertEquals(3, totalInWindow, "31 天前的不计入窗口");
        assertTrue(daily.stream().allMatch(d -> d.getDate().matches("\\d{4}-\\d{2}-\\d{2}")));
    }

    private DashboardVO.DailyItem findDaily(List<DashboardVO.DailyItem> daily, java.time.LocalDate date) {
        return daily.stream()
                .filter(d -> d.getDate().equals(date.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少日期: " + date));
    }

    @Test
    void sourceDistAlwaysThreeKeys() {
        LocalDateTime now = LocalDateTime.now();
        executionMapper.insert(exec(wf1, "success", "manual", 10, BigDecimal.ZERO, now));
        executionMapper.insert(exec(wf1, "success", "manual", 10, BigDecimal.ZERO, now));
        executionMapper.insert(exec(wf1, "success", "scheduled", 10, BigDecimal.ZERO, now));
        // 不插 api：应补 0

        var dist = statsService.dashboard().getSourceDist();
        assertEquals(3, dist.size());
        assertEquals(2L, dist.get("manual"));
        assertEquals(1L, dist.get("scheduled"));
        assertEquals(0L, dist.get("api"), "缺的来源补 0");
    }

    @Test
    void topWorkflowsOrderedByCountDesc() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            executionMapper.insert(exec(wf1, "success", "manual", 10, BigDecimal.ZERO, now));
        }
        for (int i = 0; i < 5; i++) {
            executionMapper.insert(exec(wf2, "success", "manual", 10, BigDecimal.ZERO, now));
        }

        List<DashboardVO.TopWorkflow> top = statsService.dashboard().getTopWorkflows();
        assertEquals(2, top.size());
        assertEquals(wf2, top.get(0).getWorkflowId(), "次数多的排前面");
        assertEquals("dash-wf-2", top.get(0).getName());
        assertEquals(5, top.get(0).getExecutions());
        assertEquals(3, top.get(1).getExecutions());
    }

    @Test
    void enabledSchedulesCounted() {
        WorkflowSchedule s1 = new WorkflowSchedule();
        s1.setWorkflowId(wf1);
        s1.setCron("0 0 1 * * ?");
        s1.setEnabled(1);
        scheduleMapper.insert(s1);
        WorkflowSchedule s2 = new WorkflowSchedule();
        s2.setWorkflowId(wf2);
        s2.setCron("0 0 2 * * ?");
        s2.setEnabled(0); // 停用不计
        scheduleMapper.insert(s2);

        assertEquals(1, statsService.dashboard().getSummary().getEnabledSchedules(),
                "仅启用计划计入");
    }
}
