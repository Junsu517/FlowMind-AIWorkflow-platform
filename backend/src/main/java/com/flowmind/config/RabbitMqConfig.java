package com.flowmind.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶梯 6 队列拓扑（durable，声明幂等，RabbitAdmin 在连接建立后自动声明）：
 * executions.exchange(direct) --execution.run--> executions.queue
 *   └─ x-dead-letter-exchange=executions.dlx（消费端 reject 后死信进 DLQ）
 * 无 MQ 环境下 listener 不自启（thread 模式），应用可正常启动。
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXECUTION_EXCHANGE = "executions.exchange";
    public static final String EXECUTION_QUEUE = "executions.queue";
    public static final String EXECUTION_ROUTING_KEY = "execution.run";
    public static final String DLX_EXCHANGE = "executions.dlx";
    public static final String DLQ_QUEUE = "executions.dlq";
    public static final String RETRY_HEADER = "x-retry-count";

    @Value("${flowmind.execution.mode:thread}")
    private String executionMode;

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public DirectExchange executionExchange() {
        return ExchangeBuilder.directExchange(EXECUTION_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue executionQueue() {
        return QueueBuilder.durable(EXECUTION_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .build();
    }

    @Bean
    public Binding executionBinding() {
        return BindingBuilder.bind(executionQueue())
                .to(executionExchange())
                .with(EXECUTION_ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(EXECUTION_QUEUE);
    }

    /**
     * manual ack 由 @RabbitListener 注解的 AcknowledgeMode 控制（yml listener.simple）。
     * thread 模式（本地无 MQ）下容器不自启，避免连接失败刷日志/拖慢启动；
     * RabbitListenerConfigurer 显式注册工厂以便注入 autoStartup 开关。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAutoStartup("mqueue".equalsIgnoreCase(executionMode));
        return factory;
    }

    @Bean
    public RabbitListenerConfigurer rabbitListenerConfigurer(
            SimpleRabbitListenerContainerFactory factory) {
        return (RabbitListenerEndpointRegistrar registrar) ->
                registrar.setContainerFactory(factory);
    }
}
