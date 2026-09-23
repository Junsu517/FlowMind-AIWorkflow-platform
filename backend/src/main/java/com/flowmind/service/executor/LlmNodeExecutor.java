package com.flowmind.service.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.ExecutorResult;
import com.flowmind.engine.NodeExecutionContext;
import com.flowmind.engine.NodeExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 节点执行器：DeepSeek chat/completions 流式调用，
 * 逐 delta 回调 tokenSink，完整文本与 usage 总 token 数随 ExecutorResult 返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    /** 契约 v1.3：默认模型用规范名 deepseek-flash（旧名 deepseek-v4-flash 是服务端别名） */
    public static final String DEFAULT_MODEL = "deepseek-flash";
    private static final long TIMEOUT_MS = 60_000L;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Value("${deepseek.base-url}")
    private String baseUrl;

    @Value("${deepseek.api-key}")
    private String apiKey;

    private final ObjectMapper objectMapper;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofMillis(TIMEOUT_MS))
            .readTimeout(Duration.ofMillis(TIMEOUT_MS))
            .build();

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public ExecutorResult execute(NodeExecutionContext ctx) {
        Map<String, Object> config = ctx.getConfig() == null ? Map.of() : ctx.getConfig();
        String model = str(config.get("model"), DEFAULT_MODEL);
        String prompt = str(config.get("prompt"), "");
        String outputKey = str(config.get("output_key"), "text");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("llm 节点缺少 prompt 配置: " + ctx.getNodeKey());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未配置，无法调用 LLM 节点: " + ctx.getNodeKey());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", new Object[]{Map.of("role", "user", "content", prompt)});
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        Object temperature = config.get("temperature");
        if (temperature instanceof Number number) {
            body.put("temperature", number.doubleValue());
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(toJson(body), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() == null ? "" : response.body().string();
                throw new IllegalStateException("DeepSeek 调用失败 HTTP " + response.code()
                        + ": " + truncate(err, 200));
            }
            return readStream(response, ctx, outputKey);
        } catch (IOException e) {
            throw new IllegalStateException("DeepSeek 调用异常（60s 超时或网络错误）: " + e.getMessage(), e);
        }
    }

    /** 解析 SSE 流：按行缓冲避免分片截断，data: [DONE] 结束。 */
    private ExecutorResult readStream(Response response, NodeExecutionContext ctx, String outputKey)
            throws IOException {
        StringBuilder full = new StringBuilder();
        Integer totalTokens = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode chunk = objectMapper.readTree(payload);
                JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                if (delta.isTextual() && !delta.asText().isEmpty()) {
                    full.append(delta.asText());
                    ctx.getTokenSink().accept(delta.asText());
                }
                JsonNode usage = chunk.path("usage");
                if (usage.isObject() && usage.hasNonNull("total_tokens")) {
                    totalTokens = usage.get("total_tokens").asInt();
                }
            }
        }
        return new ExecutorResult(Map.of(outputKey, full.toString()), totalTokens == null ? 0 : totalTokens);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
    }

    private String str(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
