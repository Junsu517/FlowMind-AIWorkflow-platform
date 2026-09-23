package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.UserContext;
import com.flowmind.dto.stats.UsageVO;
import com.flowmind.entity.Execution;
import com.flowmind.entity.User;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.UserMapper;
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

/** 真实 DB 的用量统计验证：总量三字段 + 近 14 天按日聚合。 */
@SpringBootTest
class StatsServiceTest {

    @Autowired
    private StatsService statsService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ExecutionMapper executionMapper;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("stest_" + System.currentTimeMillis());
        user.setPasswordHash(new BCryptPasswordEncoder().encode("12345678"));
        user.setEmail("stest@x.com");
        userMapper.insert(user);
        userId = user.getId();
        UserContext.set(userId, "user");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        executionMapper.delete(new LambdaQueryWrapper<Execution>().eq(Execution::getUserId, userId));
        userMapper.deleteById(userId);
    }

    private Execution execution(String status, int tokens, BigDecimal cost, LocalDateTime startedAt) {
        Execution e = new Execution();
        e.setUserId(userId);
        e.setWorkflowId(999L);
        e.setStatus(status);
        e.setTotalTokens(tokens);
        e.setTotalCost(cost);
        e.setDurationMs(100L);
        e.setStartedAt(startedAt);
        e.setFinishedAt(startedAt.plusSeconds(5));
        return e;
    }

    @Test
    void usageAggregatesTotalsAndByDay() {
        LocalDateTime now = LocalDateTime.now();
        executionMapper.insert(execution("success", 100, new BigDecimal("0.010000"), now));
        executionMapper.insert(execution("success", 200, new BigDecimal("0.020000"), now.minusHours(1)));
        executionMapper.insert(execution("failed", 50, BigDecimal.ZERO, now.minusDays(20)));

        UsageVO usage = statsService.usage();

        assertEquals(3, usage.getTotalExecutions(), "含失败执行，统计全部");
        assertEquals(350, usage.getTotalTokens());
        assertEquals(0, new BigDecimal("0.030000").compareTo(usage.getTotalCost()));

        List<UsageVO.DayCountVO> byDay = usage.getExecutionsByDay();
        assertEquals(1, byDay.size(), "20 天前的不进 14 天窗口，今日两条聚合为一条");
        assertEquals(now.toLocalDate().toString(), byDay.get(0).getDate());
        assertEquals(2, byDay.get(0).getCount());
        assertTrue(byDay.get(0).getDate().matches("\\d{4}-\\d{2}-\\d{2}"), "日期为 yyyy-MM-dd 字符串");
    }
}
