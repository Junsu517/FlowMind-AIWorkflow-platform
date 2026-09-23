package com.flowmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.model.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 监听器 → SSE 事件映射的纯单测（fake 注册中心记录事件，规避 JDK 25 下 Mockito 不可用）。 */
class SseExecutionListenerTest {

    private static class RecordingRegistry extends SseEmitterRegistry {
        final List<String> events = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        RecordingRegistry() {
            super(new ObjectMapper());
        }

        @Override
        public void send(Long executionId, String event, Object data) {
            events.add(event);
            payloads.add(data);
        }
    }

    @Test
    void nodeStartedMapsEvent() {
        RecordingRegistry registry = new RecordingRegistry();
        new SseExecutionListener(42L, registry).onNodeStarted(node("llm_1", "AI 建议"));

        assertEquals(List.of("node_started"), registry.events);
        assertEquals(Map.of("nodeKey", "llm_1", "name", "AI 建议"), registry.payloads.get(0));
    }

    @Test
    void nodeCompletedMapsDuration() {
        RecordingRegistry registry = new RecordingRegistry();
        NodeResult node = node("http_1", null);
        node.setDurationMs(3200L);
        new SseExecutionListener(42L, registry).onNodeCompleted(node);

        assertEquals(List.of("node_completed"), registry.events);
        assertEquals(Map.of("nodeKey", "http_1", "durationMs", 3200L), registry.payloads.get(0));
    }

    @Test
    void nodeCompletedUsesOwnTimingAfterStarted() {
        RecordingRegistry registry = new RecordingRegistry();
        SseExecutionListener listener = new SseExecutionListener(42L, registry);
        listener.onNodeStarted(node("llm_1", "AI 建议"));
        NodeResult node = node("llm_1", null);
        node.setDurationMs(0L);
        listener.onNodeCompleted(node);

        assertEquals(Map.of("nodeKey", "llm_1", "durationMs", 0L), registry.payloads.get(1));
    }

    @Test
    void nodeSkippedMapsReason() {
        RecordingRegistry registry = new RecordingRegistry();
        NodeResult node = node("http_2", null);
        node.setErrorMessage("上游分支未激活");
        new SseExecutionListener(42L, registry).onNodeSkipped(node);

        assertEquals(List.of("node_skipped"), registry.events);
        assertEquals(Map.of("nodeKey", "http_2", "reason", "上游分支未激活"), registry.payloads.get(0));
    }

    @Test
    void tokenMapsDelta() {
        RecordingRegistry registry = new RecordingRegistry();
        new SseExecutionListener(42L, registry).onToken("llm_1", "今天");

        assertEquals(List.of("token"), registry.events);
        assertEquals(Map.of("nodeKey", "llm_1", "delta", "今天"), registry.payloads.get(0));
    }

    @Test
    void nullFieldsRenderAsEmptyString() {
        RecordingRegistry registry = new RecordingRegistry();
        new SseExecutionListener(42L, registry).onNodeStarted(node("input_1", null));

        assertEquals(Map.of("nodeKey", "input_1", "name", ""), registry.payloads.get(0));
    }

    private NodeResult node(String nodeKey, String nodeName) {
        NodeResult node = new NodeResult();
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        return node;
    }
}
