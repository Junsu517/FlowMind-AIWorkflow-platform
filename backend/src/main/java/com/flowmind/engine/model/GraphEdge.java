package com.flowmind.engine.model;

/** 引擎视角的边。sourceHandle 非空表示条件分支边。 */
public class GraphEdge {

    private String sourceNodeKey;
    private String targetNodeKey;
    private String sourceHandle;

    public GraphEdge() {
    }

    public GraphEdge(String sourceNodeKey, String targetNodeKey, String sourceHandle) {
        this.sourceNodeKey = sourceNodeKey;
        this.targetNodeKey = targetNodeKey;
        this.sourceHandle = sourceHandle;
    }

    public String getSourceNodeKey() {
        return sourceNodeKey;
    }

    public void setSourceNodeKey(String sourceNodeKey) {
        this.sourceNodeKey = sourceNodeKey;
    }

    public String getTargetNodeKey() {
        return targetNodeKey;
    }

    public void setTargetNodeKey(String targetNodeKey) {
        this.targetNodeKey = targetNodeKey;
    }

    public String getSourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        this.sourceHandle = sourceHandle;
    }

    @Override
    public String toString() {
        return sourceNodeKey + "->" + targetNodeKey + (sourceHandle == null ? "" : "(" + sourceHandle + ")");
    }
}
