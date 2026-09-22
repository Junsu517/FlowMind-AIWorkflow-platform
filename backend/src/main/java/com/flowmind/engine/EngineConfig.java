package com.flowmind.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 引擎装配：自动收集所有 NodeExecutor Bean，按 type 注册。 */
@Configuration
public class EngineConfig {

    @Bean
    public ExecutionEngine executionEngine(List<NodeExecutor> executors) {
        Map<String, NodeExecutor> registry = new HashMap<>();
        for (NodeExecutor executor : executors) {
            registry.put(executor.type(), executor);
        }
        return new ExecutionEngine(registry);
    }
}
