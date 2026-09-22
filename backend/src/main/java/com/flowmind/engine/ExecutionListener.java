package com.flowmind.engine;

import com.flowmind.engine.model.NodeResult;

/**
 * 执行过程监听器：阶梯 3 的 SSE 推送与单测的录制都实现此接口。
 * 所有回调方法默认空实现，按需覆写。
 */
public interface ExecutionListener {

    default void onNodeStarted(NodeResult node) {
    }

    default void onNodeCompleted(NodeResult node) {
    }

    default void onNodeSkipped(NodeResult node) {
    }

    default void onNodeFailed(NodeResult node) {
    }

    /** LLM 流式 token 增量。 */
    default void onToken(String nodeKey, String delta) {
    }
}
