package com.flowmind.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/** 执行器的返回结果。 */
public class ExecutorResult {

    private Map<String, Object> output = new LinkedHashMap<>();
    private int tokens;

    public ExecutorResult() {
    }

    public ExecutorResult(Map<String, Object> output, int tokens) {
        this.output = output;
        this.tokens = tokens;
    }

    public static ExecutorResult of(String key, Object value) {
        ExecutorResult r = new ExecutorResult();
        r.output.put(key, value);
        return r;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }

    public int getTokens() {
        return tokens;
    }

    public void setTokens(int tokens) {
        this.tokens = tokens;
    }
}
