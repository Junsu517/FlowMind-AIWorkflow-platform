package com.flowmind.dto.stats;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class UsageVO {

    private long totalExecutions;
    private long totalTokens;
    private BigDecimal totalCost;
    private List<DayCountVO> executionsByDay;

    @Data
    @AllArgsConstructor
    public static class DayCountVO {
        private String date;
        private long count;
    }
}
