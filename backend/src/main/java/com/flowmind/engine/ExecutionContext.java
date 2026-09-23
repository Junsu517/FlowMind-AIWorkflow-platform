package com.flowmind.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行上下文：key = nodeKey，value = 节点输出的 Map。
 * 模板引用 "llm_1.text" 即从 data["llm_1"]["text"] 取值。
 * 阶梯 3 起为并行调度，使用 ConcurrentHashMap 保证多执行线程安全；
 * 节点按 nodeKey 隔离写入，天然无写冲突。
 */
public class ExecutionContext {

    private final Map<String, Object> data = new ConcurrentHashMap<>();

    /** 将节点输出整体写入，如 context.putNodeOutput("llm_1", {"text": "..."})。 */
    public void putNodeOutput(String nodeKey, Map<String, Object> output) {
        if (output != null) {
            data.put(nodeKey, output);
        }
    }

    public Object getNodeOutput(String nodeKey) {
        return data.get(nodeKey);
    }

    public boolean contains(String nodeKey) {
        return data.containsKey(nodeKey);
    }

    public Map<String, Object> snapshot() {
        return new java.util.LinkedHashMap<>(data);
    }
}
