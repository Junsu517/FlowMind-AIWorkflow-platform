package com.flowmind.engine.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 一次执行的完整结果。 */
public class ExecutionReport {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    private String status;
    private Map<String, Object> output = new HashMap<>();
    private List<NodeResult> nodeResults = new ArrayList<>();
    private int totalTokens;
    private long durationMs;
    private String errorMessage;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }

    public List<NodeResult> getNodeResults() {
        return nodeResults;
    }

    public void setNodeResults(List<NodeResult> nodeResults) {
        this.nodeResults = nodeResults;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
