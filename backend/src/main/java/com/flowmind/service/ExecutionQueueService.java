package com.flowmind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.config.RabbitMqConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 阶梯 6 MQ 链路：publish（publisher confirm）+ 消费（manual ack、幂等、重试计数、死信）。
 * 重试策略选立即 republish（不带 TTL 退避）：执行失败多为准确定性错误（外部服务/DB），
 * 立即重试 3 次足够暴露问题且拓扑简单；退避需额外延迟队列，留二期（理由见汇报）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionQueueService {

    static final int MAX_RETRY = 3;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutionRunner executionRunner;

    /**
     * 发布执行消息（持久化 + publisher confirm）。
     * MQ 不可达抛 IllegalStateException（submit 侧回置行并释放锁）；
     * confirm nack/超时在回调里把执行行翻 failed（消息可能未达）。
     */
    public void publish(Long executionId) {
        CorrelationData correlation = new CorrelationData("exec-" + executionId);
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXECUTION_EXCHANGE,
                    RabbitMqConfig.EXECUTION_ROUTING_KEY,
                    buildMessage(executionId, 0),
                    m -> m, correlation);
        } catch (AmqpException e) {
            throw new IllegalStateException("消息队列不可达: " + e.getMessage(), e);
        }
        correlation.getFuture().whenComplete((confirm, ex) -> {
            if (ex != null || confirm == null || !confirm.isAck()) {
                log.error("publisher confirm 失败，执行行翻 failed: executionId={}", executionId, ex);
                executionRunner.markFailed(executionId,
                        "消息投递确认失败（MQ 未收到，请重试）");
            }
        });
    }

    @RabbitListener(queues = RabbitMqConfig.EXECUTION_QUEUE)
    public void onExecutionMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long executionId = parseExecutionId(message);
        if (executionId == null) {
            ackQuietly(channel, tag);
            return;
        }
        int retryCount = parseRetryCount(message);
        try {
            executionRunner.runByExecutionId(executionId);
        } catch (Exception e) {
            log.error("消费失败（第 {} 次）: executionId={}", retryCount + 1, executionId, e);
            if (retryCount < MAX_RETRY) {
                // 接力重试：发布带递增计数头的新消息后确认原消息（避免 requeue 死循环）
                try {
                    rabbitTemplate.convertAndSend(RabbitMqConfig.EXECUTION_EXCHANGE,
                            RabbitMqConfig.EXECUTION_ROUTING_KEY,
                            buildMessage(executionId, retryCount + 1));
                    ackQuietly(channel, tag);
                } catch (AmqpException republishError) {
                    log.error("重试消息发布失败，原消息重回队列: executionId={}", executionId, republishError);
                    try {
                        channel.basicNack(tag, false, true);
                    } catch (IOException nackError) {
                        log.warn("nack 失败（消息将由 requeue 兜底）: executionId={}", executionId);
                    }
                }
            } else {
                // 达到上限：reject（不重回）→ 经 DLX 死信进 DLQ
                try {
                    channel.basicReject(tag, false);
                } catch (IOException rejectError) {
                    log.warn("reject 失败（消息将由 requeue 兜底）: executionId={}", executionId);
                }
            }
            return;
        }
        // 执行已成功（行已终态落库）：ack 失败仅意味着消息会重投，由幂等消化，不算执行失败
        ackQuietly(channel, tag);
    }

    /** ack 与业务执行解耦：channel 抖动（隧道/网络）时只告警，避免误入重试分支。 */
    private void ackQuietly(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.warn("basicAck 失败（消息将重投，由消费幂等消化）: tag={}, {}", tag, e.getMessage());
        }
    }

    @RabbitListener(queues = RabbitMqConfig.DLQ_QUEUE)
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long executionId = parseExecutionId(message);
        try {
            if (executionId != null) {
                executionRunner.markFailed(executionId, "任务处理失败（已重试 " + MAX_RETRY + " 次）");
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private Message buildMessage(Long executionId, int retryCount) {
        String body = "{\"executionId\":" + executionId + "}";
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8),
                new org.springframework.amqp.core.MessageProperties());
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        message.getMessageProperties().setHeader(RabbitMqConfig.RETRY_HEADER, retryCount);
        return message;
    }

    private Long parseExecutionId(Message message) {
        try {
            JsonNode node = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            return node.hasNonNull("executionId") ? node.get("executionId").asLong() : null;
        } catch (Exception e) {
            log.warn("消息体解析失败，丢弃: {}", e.getMessage());
            return null;
        }
    }

    static int parseRetryCount(Message message) {
        Object count = message.getMessageProperties().getHeader(RabbitMqConfig.RETRY_HEADER);
        return count instanceof Number number ? number.intValue() : 0;
    }
}
