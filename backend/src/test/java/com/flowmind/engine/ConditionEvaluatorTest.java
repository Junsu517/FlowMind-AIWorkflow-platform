package com.flowmind.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    @Test
    void 命中第一条规则() {
        Map<String, Object> config = Map.of(
                "rules", List.of(
                        Map.of("left", "南昌", "operator", "equals", "right", "南昌", "handle", "nc"),
                        Map.of("left", "南昌", "operator", "equals", "right", "北京", "handle", "bj")),
                "default_handle", "other");
        Map<String, Object> result = evaluator.evaluate(config);
        assertEquals("nc", result.get("matchedHandle"));
        assertEquals(0, result.get("ruleIndex"));
    }

    @Test
    void 全不命中走default() {
        Map<String, Object> config = Map.of(
                "rules", List.of(Map.of("left", "上海", "operator", "equals", "right", "北京", "handle", "bj")),
                "default_handle", "other");
        Map<String, Object> result = evaluator.evaluate(config);
        assertEquals("other", result.get("matchedHandle"));
        assertEquals(-1, result.get("ruleIndex"));
    }

    @Test
    void contains与notEmpty操作符() {
        assertEquals("hit", evaluator.evaluate(Map.of(
                "rules", List.of(Map.of("left", "金毛掉毛", "operator", "contains", "right", "掉毛", "handle", "hit")),
                "default_handle", "miss")).get("matchedHandle"));

        assertEquals("hit", evaluator.evaluate(Map.of(
                "rules", List.of(Map.of("left", "有内容", "operator", "not_empty", "right", "", "handle", "hit")),
                "default_handle", "miss")).get("matchedHandle"));
    }
}
