package com.flowmind.engine;

import java.util.Map;
import java.util.function.Consumer;

/** 单个节点的执行输入（配置已渲染）。 */
public class NodeExecutionContext {

    private final String nodeKey;
    private final String nodeName;
    private final String nodeType;
    private final Map<String, Object> config;
    private final ExecutionContext context;
    /** LLM 流式 token 回调，可为 null（无流式消费方）。 */
    private final Consumer<String> tokenSink;

    public NodeExecutionContext(String nodeKey, String nodeName, String nodeType,
                                Map<String, Object> config, ExecutionContext context,
                                Consumer<String> tokenSink) {
        this.nodeKey = nodeKey;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
        this.config = config;
        this.context = context;
        this.tokenSink = tokenSink;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getNodeType() {
        return nodeType;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public ExecutionContext getContext() {
        return context;
    }

    public Consumer<String> getTokenSink() {
        return tokenSink;
    }
}
