package com.flowmind.service.executor;

import com.flowmind.engine.ExecutorResult;
import com.flowmind.engine.NodeExecutionContext;
import com.flowmind.engine.NodeExecutor;
import com.flowmind.entity.Tool;
import com.flowmind.mapper.ToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HTTP 工具节点执行器：按 tool_id 查 tools 表，
 * 用已渲染的 params 经 HttpToolInvoker 调用，响应挂到 output_key。
 * 与 LlmNodeExecutor 的 Function Calling 共用 HttpToolInvoker。
 */
@Component
@RequiredArgsConstructor
public class HttpNodeExecutor implements NodeExecutor {

    private final ToolMapper toolMapper;
    private final HttpToolInvoker httpToolInvoker;

    @Override
    public String type() {
        return "http";
    }

    @Override
    public ExecutorResult execute(NodeExecutionContext ctx) {
        Map<String, Object> config = ctx.getConfig() == null ? Map.of() : ctx.getConfig();
        Object toolIdObj = config.get("tool_id");
        if (!(toolIdObj instanceof Number toolId)) {
            throw new IllegalArgumentException("http 节点缺少 tool_id 配置: " + ctx.getNodeKey());
        }
        Tool tool = toolMapper.selectById(toolId.longValue());
        if (tool == null || tool.getEnabled() == null || tool.getEnabled() != 1) {
            throw new IllegalStateException("工具不存在或已禁用: tool_id=" + toolId);
        }

        Map<String, Object> params = config.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String outputKey = config.get("output_key") == null ? "data" : String.valueOf(config.get("output_key"));

        return new ExecutorResult(Map.of(outputKey, httpToolInvoker.invoke(tool, params)), 0);
    }
}
