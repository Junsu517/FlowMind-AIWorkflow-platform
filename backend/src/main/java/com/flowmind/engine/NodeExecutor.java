package com.flowmind.engine;

/**
 * 节点执行器接口：GLM 在 service 包实现 LLM / HTTP 两类 IO 执行器，
 * 以 Spring Bean 注册，引擎自动发现。input 与 condition 由引擎内置处理。
 */
public interface NodeExecutor {

    /** 支持的类型：llm / http。 */
    String type();

    /** 执行节点，返回节点输出（含 token 统计）。 */
    ExecutorResult execute(NodeExecutionContext ctx);
}
