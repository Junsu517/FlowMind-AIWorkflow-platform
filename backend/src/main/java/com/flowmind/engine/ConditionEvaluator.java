package com.flowmind.engine;

import java.util.List;
import java.util.Map;

/** 条件节点求值：顺序匹配 rules，命中返回 handle；全不命中返回 defaultHandle。 */
public class ConditionEvaluator {

    public static final String OP_EQUALS = "equals";
    public static final String OP_NOT_EQUALS = "not_equals";
    public static final String OP_CONTAINS = "contains";
    public static final String OP_NOT_EMPTY = "not_empty";

    /** 返回命中结果：{matchedHandle, ruleIndex}；全不命中时 matchedHandle=defaultHandle、ruleIndex=-1。 */
    public Map<String, Object> evaluate(Map<String, Object> config) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.getOrDefault("rules", List.of());
        String defaultHandle = (String) config.getOrDefault("default_handle", "default");

        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            String left = String.valueOf(rule.getOrDefault("left", ""));
            String op = (String) rule.getOrDefault("operator", OP_EQUALS);
            String right = String.valueOf(rule.getOrDefault("right", ""));
            if (match(left, op, right)) {
                return Map.of(
                        "matchedHandle", String.valueOf(rule.getOrDefault("handle", defaultHandle)),
                        "ruleIndex", i
                );
            }
        }
        return Map.of("matchedHandle", defaultHandle, "ruleIndex", -1);
    }

    private boolean match(String left, String op, String right) {
        return switch (op) {
            case OP_EQUALS -> left.equals(right);
            case OP_NOT_EQUALS -> !left.equals(right);
            case OP_CONTAINS -> left.contains(right);
            case OP_NOT_EMPTY -> left != null && !left.isEmpty();
            default -> false;
        };
    }
}
