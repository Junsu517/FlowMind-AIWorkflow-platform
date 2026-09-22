package com.flowmind.engine.model;

import java.util.Map;

/** 引擎视角的节点（与持久层解耦，config 为已解析的 Map）。 */
public class GraphNode {

    private String nodeKey;
    private String type;
    private String name;
    private Map<String, Object> config;

    public GraphNode() {
    }

    public GraphNode(String nodeKey, String type, String name, Map<String, Object> config) {
        this.nodeKey = nodeKey;
        this.type = type;
        this.name = name;
        this.config = config;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    @Override
    public String toString() {
        return "GraphNode{key=" + nodeKey + ", type=" + type + "}";
    }
}
