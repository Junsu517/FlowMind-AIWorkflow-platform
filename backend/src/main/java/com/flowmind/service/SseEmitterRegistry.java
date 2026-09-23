package com.flowmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接注册中心：一个 execution 一个 emitter。
 * emitter 不存在（客户端未连或已断开）时事件静默丢弃——连接断开不影响执行（契约要求）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEmitterRegistry {

    private static final long TIMEOUT_MS = 5 * 60_000L;

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseEmitter getOrCreate(Long executionId) {
        return emitters.computeIfAbsent(executionId, id -> {
            SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
            emitter.onCompletion(() -> emitters.remove(id));
            emitter.onTimeout(() -> emitters.remove(id));
            emitter.onError(e -> emitters.remove(id));
            return emitter;
        });
    }

    public void send(Long executionId, String event, Object data) {
        SseEmitter emitter = emitters.get(executionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端已断开：移除 emitter，不再向其发送
            emitters.remove(executionId);
            log.debug("SSE 发送失败，emitter 已移除: executionId={}, event={}", executionId, event);
        }
    }

    public void complete(Long executionId) {
        SseEmitter emitter = emitters.remove(executionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // complete 失败说明连接已不在，无需处理
            }
        }
    }
}
