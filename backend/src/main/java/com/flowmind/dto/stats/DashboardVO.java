package com.flowmind.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 契约 v1.9：字段名一个不许改（前端按契约写死） */
@Data
@AllArgsConstructor
public class DashboardVO {

    private Summary summary;
    private List<DailyItem> daily;
    private Map<String, Long> sourceDist;
    private List<TopWorkflow> topWorkflows;

    @Data
    @AllArgsConstructor
    public static class Summary {
        private long totalExecutions;
        private double successRate;
        private long totalTokens;
        private BigDecimal totalCost;
        private long activeWorkflows;
        private long knowledgeDocs;
        private long publishedApis;
        private long enabledSchedules;
    }

    @Data
    @AllArgsConstructor
    public static class DailyItem {
        private String date;
        private long executions;
        private long tokens;
    }

    @Data
    @AllArgsConstructor
    public static class TopWorkflow {
        private Long workflowId;
        private String name;
        private long executions;
    }
}
