package com.flowmind.service;

import com.flowmind.common.UserContext;
import com.flowmind.dto.stats.UsageVO;
import com.flowmind.mapper.ExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    private static final int RECENT_DAYS = 14;

    private final ExecutionMapper executionMapper;

    public UsageVO usage() {
        Long userId = UserContext.getUserId();
        Map<String, Object> totals = executionMapper.selectUserUsage(userId);
        LocalDateTime since = LocalDate.now().minusDays(RECENT_DAYS - 1L).atStartOfDay();
        List<Map<String, Object>> byDay = executionMapper.selectExecutionsByDay(userId, since);

        List<UsageVO.DayCountVO> dayCounts = byDay.stream()
                .map(row -> new UsageVO.DayCountVO(
                        String.valueOf(row.get("date")),
                        ((Number) row.get("count")).longValue()))
                .toList();

        return new UsageVO(
                ((Number) totals.get("total_executions")).longValue(),
                ((Number) totals.get("total_tokens")).longValue(),
                new BigDecimal(String.valueOf(totals.get("total_cost"))),
                dayCounts);
    }
}
