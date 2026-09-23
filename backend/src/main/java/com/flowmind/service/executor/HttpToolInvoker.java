package com.flowmind.service.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.entity.Tool;
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
 * HTTP 工具公共调用器：HttpNodeExecutor（节点直调）与 LlmNodeExecutor（Function Calling）共用。
 * 职责：渲染 url 模板占位符 → 同步 GET 调用 → 响应解析为 Map。
 */
@Component
@RequiredArgsConstructor
public class HttpToolInvoker {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final long TIMEOUT_MS = 10_000L;

    private final ObjectMapper objectMapper;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofMillis(TIMEOUT_MS))
            .readTimeout(Duration.ofMillis(TIMEOUT_MS))
            .build();

    /**
     * 调用工具，返回解析后的 JSON Map（非 JSON 响应包 {"raw": ...}）。
     * 超时/非 200/缺参抛 IllegalStateException，由调用方决定节点失败还是回填模型。
     */
    public Map<String, Object> invoke(Tool tool, Map<String, Object> params) {
        if (!"GET".equalsIgnoreCase(tool.getMethod())) {
            throw new IllegalArgumentException("阶梯 1 仅支持 GET 工具: " + tool.getName());
        }
        String url = renderUrl(tool.getUrlTemplate(), params);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("工具调用失败 HTTP " + response.code() + ": " + tool.getName());
            }
            String body = response.body() == null ? "" : response.body().string();
            return parseJson(body);
        } catch (IOException e) {
            throw new IllegalStateException("工具调用异常（10s 超时或网络错误）: " + tool.getName() + ", " + e.getMessage(), e);
        }
    }

    /** {{param}} 占位符替换；缺参直接抛错，避免把未渲染的 URL 发出去。 */
    public String renderUrl(String template, Map<String, Object> params) {
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
