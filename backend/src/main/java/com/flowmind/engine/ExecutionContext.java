package com.flowmind.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行上下文：key = nodeKey，value = 节点输出的 Map。
 * 模板引用 "llm_1.text" 即从 data["llm_1"]["text"] 取值。
 * 阶梯 1 顺序执行无并发；阶梯 3 并行调度时换 ConcurrentHashMap，接口不变。
 */
public class ExecutionContext {

    private final Map<String, Object> data = new LinkedHashMap<>();

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
        return new LinkedHashMap<>(data);
    }
}
