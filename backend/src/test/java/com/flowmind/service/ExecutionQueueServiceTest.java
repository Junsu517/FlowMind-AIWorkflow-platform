package com.flowmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.config.RabbitMqConfig;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ 消费逻辑单测（不连真实 RabbitMQ）：
 * 手写 RabbitTemplate/Channel/ExecutionRunner 的 stub（JDK 25 下 Mockito mock 类不可用），
 * 验证 ack 决策、重试计数、DLQ 流转。
 */
class ExecutionQueueServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 记录 convertAndSend 调用的 RabbitTemplate stub。 */
    private static class RecordingTemplate extends RabbitTemplate {
        final List<String> sentRoutingKeys = new ArrayList<>();
        final List<Integer> sentRetryCounts = new ArrayList<>();

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message) {
            record(message);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message,
                                   CorrelationData correlationData) throws RuntimeException {
            record(message);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message,
                                   MessagePostProcessor messagePostProcessor) throws RuntimeException {
            record(message);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message,
                                   MessagePostProcessor messagePostProcessor,
                                   CorrelationData correlationData) throws RuntimeException {
            record(message);
        }

        private void record(Object message) {
            sentRoutingKeys.add("sent");
            if (message instanceof Message m) {
                Object retry = m.getMessageProperties().getHeader(RabbitMqConfig.RETRY_HEADER);
                sentRetryCounts.add(retry instanceof Number n ? n.intValue() : 0);
            }
        }
    }

    /** 可编程 ExecutionRunner stub。 */
    private static class StubRunner extends ExecutionRunner {
        int runCalls;
        int markFailedCalls;
        String lastMarkFailedMessage;
        RuntimeException throwOnRun;

        StubRunner() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public void runByExecutionId(Long executionId) {
            runCalls++;
            if (throwOnRun != null) {
                throw throwOnRun;
            }
        }

        @Override
        public void markFailed(Long executionId, String errorMessage) {
            markFailedCalls++;
            lastMarkFailedMessage = errorMessage;
        }
    }

    /** Channel stub（JDK Proxy），记录 ack/reject。 */
    private static class ChannelRecorder {
        final AtomicLong acked = new AtomicLong();
        final AtomicLong nacked = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();

        Channel stub() {
            return (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                    new Class<?>[]{Channel.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "basicAck" -> acked.incrementAndGet();
                            case "basicNack" -> nacked.incrementAndGet();
                            case "basicReject" -> rejected.incrementAndGet();
                            default -> {
                            }
                        }
                        return null;
                    });
        }
    }

    private Message message(long executionId, int retryCount) {
        Message msg = new Message(
                ("{\"executionId\":" + executionId + "}").getBytes(StandardCharsets.UTF_8),
                new org.springframework.amqp.core.MessageProperties());
        msg.getMessageProperties().setHeader(RabbitMqConfig.RETRY_HEADER, retryCount);
        return msg;
    }

    @Test
    void successfulRunAcks() throws Exception {
        RecordingTemplate template = new RecordingTemplate();
        StubRunner runner = new StubRunner();
        ChannelRecorder recorder = new ChannelRecorder();
        ExecutionQueueService service = new ExecutionQueueService(template, objectMapper, runner);

        service.onExecutionMessage(message(1L, 0), recorder.stub());

        assertEquals(1, runner.runCalls);
        assertEquals(1, recorder.acked.get());
        assertEquals(0, recorder.rejected.get());
    }

    @Test
    void failureBelowLimitRepublishesWithIncrementedCount() throws Exception {
        RecordingTemplate template = new RecordingTemplate();
        StubRunner runner = new StubRunner();
        runner.throwOnRun = new RuntimeException("db down");
        ChannelRecorder recorder = new ChannelRecorder();
        ExecutionQueueService service = new ExecutionQueueService(template, objectMapper, runner);

        service.onExecutionMessage(message(2L, 0), recorder.stub());

        assertEquals(1, template.sentRetryCounts.size());
        assertEquals(1, (int) template.sentRetryCounts.get(0), "重试计数应递增为 1");
        assertEquals(1, recorder.acked.get(), "原消息应 ack（新消息接力）");
        assertEquals(0, recorder.rejected.get());
    }

    @Test
    void failureAtLimitRejectsToDeadLetter() throws Exception {
        RecordingTemplate template = new RecordingTemplate();
        StubRunner runner = new StubRunner();
        runner.throwOnRun = new RuntimeException("still failing");
        ChannelRecorder recorder = new ChannelRecorder();
        ExecutionQueueService service = new ExecutionQueueService(template, objectMapper, runner);

        service.onExecutionMessage(message(3L, ExecutionQueueService.MAX_RETRY), recorder.stub());

        assertEquals(0, template.sentRetryCounts.size(), "达到上限不再 republish");
        assertEquals(1, recorder.rejected.get(), "应 reject 进死信");
        assertEquals(0, recorder.acked.get());
    }

    @Test
    void deadLetterMarksFailedWithRetryMessage() throws Exception {
        RecordingTemplate template = new RecordingTemplate();
        StubRunner runner = new StubRunner();
        ChannelRecorder recorder = new ChannelRecorder();
        ExecutionQueueService service = new ExecutionQueueService(template, objectMapper, runner);

        service.onDeadLetter(message(4L, ExecutionQueueService.MAX_RETRY), recorder.stub());

        assertEquals(1, runner.markFailedCalls);
        assertTrue(runner.lastMarkFailedMessage.contains("已重试 3 次"));
        assertEquals(1, recorder.acked.get());
    }

    @Test
    void malformedMessageAckedAndDropped() throws Exception {
        RecordingTemplate template = new RecordingTemplate();
        StubRunner runner = new StubRunner();
        ChannelRecorder recorder = new ChannelRecorder();
        ExecutionQueueService service = new ExecutionQueueService(template, objectMapper, runner);

        Message bad = new Message("not json".getBytes(StandardCharsets.UTF_8),
                new org.springframework.amqp.core.MessageProperties());
        service.onExecutionMessage(bad, recorder.stub());

        assertEquals(0, runner.runCalls, "坏消息不执行");
        assertEquals(1, recorder.acked.get(), "坏消息直接 ack 丢弃");
    }

    @Test
    void retryCountHeaderDefaultsToZero() {
        Message noHeader = new Message("{\"executionId\":1}".getBytes(StandardCharsets.UTF_8),
                new org.springframework.amqp.core.MessageProperties());
        assertEquals(0, ExecutionQueueService.parseRetryCount(noHeader));
        assertEquals(2, ExecutionQueueService.parseRetryCount(message(1L, 2)));
    }
}
