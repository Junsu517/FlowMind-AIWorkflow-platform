package com.flowmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowmind.common.UserContext;
import com.flowmind.dto.stats.DashboardVO;
import com.flowmind.dto.stats.UsageVO;
import com.flowmind.entity.Workflow;
import com.flowmind.entity.WorkflowApiKey;
import com.flowmind.entity.WorkflowSchedule;
import com.flowmind.entity.KnowledgeDocument;
import com.flowmind.entity.KnowledgeBase;
import com.flowmind.mapper.ExecutionMapper;
import com.flowmind.mapper.KnowledgeDocumentMapper;
import com.flowmind.mapper.WorkflowApiKeyMapper;
import com.flowmind.mapper.WorkflowMapper;
import com.flowmind.mapper.WorkflowScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    private static final int RECENT_DAYS = 14;
    private static final int DASHBOARD_DAYS = 30;
    private static final List<String> SOURCE_KEYS = List.of("manual", "scheduled", "api");

    private final ExecutionMapper executionMapper;
    private final WorkflowMapper workflowMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final WorkflowApiKeyMapper apiKeyMapper;
    private final WorkflowScheduleMapper scheduleMapper;

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

    /** 阶梯 9 仪表盘：分查询组装（一次返回全部块）。 */
    public DashboardVO dashboard() {
        Long userId = UserContext.getUserId();

        Map<String, Object> s = executionMapper.selectDashboardSummary(userId);
        long success = num(s.get("success_count"));
        long failed = num(s.get("failed_count"));
        // 口径：进行中（queued/running）不计入分母
        double successRate = (success + failed) == 0 ? 0.0
                : Math.round(success * 10000.0 / (success + failed)) / 10000.0;
        long activeWorkflows = workflowMapper.selectCount(
                new LambdaQueryWrapper<Workflow>().eq(Workflow::getUserId, userId));

        DashboardVO.Summary summary = new DashboardVO.Summary(
                num(s.get("total")),
                successRate,
                num(s.get("total_tokens")),
                new BigDecimal(String.valueOf(s.get("total_cost"))),
                activeWorkflows,
                countKnowledgeDocs(userId),
                apiKeyMapper.selectCount(new LambdaQueryWrapper<WorkflowApiKey>()
                        .inSql(WorkflowApiKey::getWorkflowId,
                                "SELECT id FROM workflows WHERE user_id = " + userId)),
                scheduleMapper.selectCount(new LambdaQueryWrapper<WorkflowSchedule>()
                        .eq(WorkflowSchedule::getEnabled, 1)
                        .inSql(WorkflowSchedule::getWorkflowId,
                                "SELECT id FROM workflows WHERE user_id = " + userId)));

        List<DashboardVO.DailyItem> daily = fillDailyZeroes(
                executionMapper.selectDashboardDaily(userId,
                        LocalDate.now().minusDays(DASHBOARD_DAYS - 1L).atStartOfDay()));

        Map<String, Long> sourceDist = new LinkedHashMap<>();
        SOURCE_KEYS.forEach(key -> sourceDist.put(key, 0L));
        executionMapper.selectDashboardSourceDist(userId)
                .forEach(row -> sourceDist.put(String.valueOf(row.get("source")), num(row.get("count"))));

        List<DashboardVO.TopWorkflow> top = executionMapper.selectDashboardTopWorkflows(userId)
                .stream()
                .map(row -> new DashboardVO.TopWorkflow(
                        ((Number) row.get("workflowId")).longValue(),
                        String.valueOf(row.get("name")),
                        num(row.get("executions"))))
                .toList();

        return new DashboardVO(summary, daily, sourceDist, top);
    }

    private long countKnowledgeDocs(Long userId) {
        Long docs = knowledgeDocumentMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .inSql(KnowledgeDocument::getKbId,
                                "SELECT id FROM knowledge_bases WHERE user_id = " + userId));
        return docs == null ? 0 : docs;
    }

    /** 近 30 天无数据日期补 0，固定返回 30 项（含今天）。 */
    private List<DashboardVO.DailyItem> fillDailyZeroes(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        rows.forEach(row -> byDate.put(String.valueOf(row.get("date")), row));
        List<DashboardVO.DailyItem> result = new ArrayList<>(DASHBOARD_DAYS);
        for (int i = DASHBOARD_DAYS - 1; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).toString();
            Map<String, Object> row = byDate.get(date);
            result.add(new DashboardVO.DailyItem(date,
                    row == null ? 0 : num(row.get("executions")),
                    row == null ? 0 : num(row.get("tokens"))));
        }
        return result;
    }

    private long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
