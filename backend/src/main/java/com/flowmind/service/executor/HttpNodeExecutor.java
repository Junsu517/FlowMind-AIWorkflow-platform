package com.flowmind.service.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.ExecutorResult;
import com.flowmind.engine.NodeExecutionContext;
import com.flowmind.engine.NodeExecutor;
import com.flowmind.entity.Tool;
import com.flowmind.mapper.ToolMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 工具节点执行器：按 tool_id 查 tools 表，
 * 用已渲染的 params 替换 url 模板中的 {{param}} 占位符后同步调用，响应解析为 Map。
 */
@Component
@RequiredArgsConstructor
public class HttpNodeExecutor implements NodeExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final long TIMEOUT_MS = 10_000L;

    private final ToolMapper toolMapper;
    private final ObjectMapper objectMapper;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofMillis(TIMEOUT_MS))
            .readTimeout(Duration.ofMillis(TIMEOUT_MS))
            .build();

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
        if (!"GET".equalsIgnoreCase(tool.getMethod())) {
            throw new IllegalArgumentException("阶梯 1 仅支持 GET 工具: " + tool.getName());
        }

        Map<String, Object> params = config.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String url = renderUrl(tool.getUrlTemplate(), params);
        String outputKey = config.get("output_key") == null ? "data" : String.valueOf(config.get("output_key"));

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("工具调用失败 HTTP " + response.code() + ": " + tool.getName());
            }
            String body = response.body() == null ? "" : response.body().string();
            return new ExecutorResult(Map.of(outputKey, parseJson(body)), 0);
        } catch (IOException e) {
            throw new IllegalStateException("工具调用异常（10s 超时或网络错误）: " + tool.getName() + ", " + e.getMessage(), e);
        }
    }

    /** {{param}} 占位符替换；缺参直接抛错，避免把未渲染的 URL 发出去。 */
    private String renderUrl(String template, Map<String, Object> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = params.get(key);
            if (value == null) {
                throw new IllegalArgumentException("工具参数未提供: " + key);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            // 非 JSON 响应原样保留，不阻断流程
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("raw", body);
            return raw;
        }
    }
}
