package com.flowmind.service.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.ExecutorResult;
import com.flowmind.engine.NodeExecutionContext;
import com.flowmind.engine.NodeExecutor;
import com.flowmind.entity.Tool;
import com.flowmind.mapper.ToolMapper;
import com.flowmind.service.rag.RagService;
import com.flowmind.service.rag.RetrievedChunk;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LLM 节点执行器。
 * 无 tools 配置：单轮 DeepSeek 流式调用，delta 实时推 tokenSink。
 * 有 tools 配置（契约 v1.4）：代理循环——模型请求工具 → 执行（HttpToolInvoker）→ 结果回填 → 再问，
 * 直到模型给出最终文本或达到 max_tool_rounds 截断。仅最终轮 content 推 tokenSink（缓冲后重放，保打字机效果）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    /** 契约 v1.3：默认模型用规范名 deepseek-flash（旧名 deepseek-v4-flash 是服务端别名） */
    public static final String DEFAULT_MODEL = "deepseek-flash";
    static final int DEFAULT_MAX_TOOL_ROUNDS = 3;
    private static final long TIMEOUT_MS = 60_000L;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Value("${deepseek.base-url}")
    private String baseUrl;

    @Value("${deepseek.api-key}")
    private String apiKey;

    private final ToolMapper toolMapper;
    private final HttpToolInvoker httpToolInvoker;
    private final RagService ragService;
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

        List<Tool> tools = loadTools(config.get("tools"));
        // RAG 分支（阶梯 5）：配置了 knowledge_base_id 时先检索，检索结果注入 system 消息；
        // 未配置时 references 为 null，后续逻辑与原路径完全一致
        List<RetrievedChunk> references = retrieveIfConfigured(config.get("knowledge_base_id"), prompt);
        if (tools.isEmpty()) {
            return executeSingle(ctx, model, prompt, outputKey, config.get("temperature"), references);
        }
        int maxRounds = maxToolRounds(config.get("max_tool_rounds"));
        return executeWithTools(ctx, model, prompt, outputKey, config.get("temperature"), tools, maxRounds, references);
    }

    /** 渲染后的 prompt 作为检索 query（方案 §3：MVP 简化）；检索失败节点 failed（用户显式配置了知识库）。 */
    private List<RetrievedChunk> retrieveIfConfigured(Object kbIdConfig, String prompt) {
        if (!(kbIdConfig instanceof Number kbId)) {
            return null;
        }
        if (ragService == null) {
            throw new IllegalStateException("知识库检索不可用（RagService 未装配）");
        }
        try {
            return ragService.retrieve(kbId.longValue(), prompt);
        } catch (Exception e) {
            throw new IllegalStateException("知识库检索失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 单轮（无 Function Calling，阶梯 1 原路径） ----------------

    private ExecutorResult executeSingle(NodeExecutionContext ctx, String model, String prompt,
                                         String outputKey, Object temperature,
                                         List<RetrievedChunk> references) {
        List<Map<String, Object>> messages = buildMessages(prompt, references);
        ChatRound round = callChatStream(model, temperature, messages, null, true, ctx.getTokenSink());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(outputKey, round.content());
        if (references != null) {
            output.put("references", ragService.toReferences(references));
        }
        return new ExecutorResult(output, round.tokens());
    }

    // ---------------- Function Calling 代理循环（阶梯 4） ----------------

    private ExecutorResult executeWithTools(NodeExecutionContext ctx, String model, String prompt,
                                            String outputKey, Object temperature,
                                            List<Tool> tools, int maxRounds,
                                            List<RetrievedChunk> references) {
        List<Map<String, Object>> toolsParam = buildToolsParam(tools);
        Map<String, Tool> toolByName = new LinkedHashMap<>();
        tools.forEach(t -> toolByName.put(t.getName(), t));

        List<Map<String, Object>> messages = buildMessages(prompt, references);

        int totalTokens = 0;
        List<Map<String, Object>> toolCallLog = new ArrayList<>();

        for (int round = 1; round <= maxRounds; round++) {
            ChatRound result = callChatStream(model, temperature, messages, toolsParam, false, null);
            totalTokens += result.tokens();

            if (result.toolCalls().isEmpty()) {
                // 最终轮：重放 content 增量到 tokenSink
                result.contentDeltas().forEach(ctx.getTokenSink()::accept);
                return buildResult(outputKey, result.content(), totalTokens, toolCallLog, references);
            }

            // 回填 assistant 消息（含 tool_calls），再逐个执行工具并回填 tool 消息
            messages.add(assistantToolCallMessage(result.toolCalls()));
            for (ToolCall call : result.toolCalls()) {
                long start = System.currentTimeMillis();
                Map<String, Object> args = parseArguments(call.arguments());
                Map<String, Object> toolResult = executeToolSafely(toolByName.get(call.name()), call.name(), args);
                long durationMs = System.currentTimeMillis() - start;
                toolCallLog.add(toolCallLogEntry(call, args, toolResult, durationMs, round));
                messages.add(toolResultMessage(call.id(), toolResult));
            }
        }

        log.warn("llm 节点达到最大工具调用轮数后模型仍请求工具，强制截断: nodeKey={}, rounds={}",
                ctx.getNodeKey(), maxRounds);
        String truncated = "[已达到最大工具调用轮数 " + maxRounds + "，执行被截断]";
        return buildResult(outputKey, truncated, totalTokens, toolCallLog, references);
    }

    /** user 消息之前按需注入 RAG 参考资料 system 消息（阶梯 5，先注入后照常走代理循环）。 */
    private List<Map<String, Object>> buildMessages(String prompt, List<RetrievedChunk> references) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (references != null && !references.isEmpty()) {
            messages.add(Map.of("role", "system", "content", ragService.buildSystemPrompt(references)));
        }
        messages.add(Map.of("role", "user", "content", prompt));
        return messages;
    }

    /** 工具执行失败不 fail 节点：错误信息回填给模型自行决策（任务卡要求）。 */
    private Map<String, Object> executeToolSafely(Tool tool, String name, Map<String, Object> args) {
        if (tool == null) {
            return Map.of("error", "未知工具: " + name);
        }
        try {
            return httpToolInvoker.invoke(tool, args);
        } catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? "工具调用失败" : e.getMessage());
        }
    }

    private Map<String, Object> toolCallLogEntry(ToolCall call, Map<String, Object> args,
                                                 Map<String, Object> toolResult,
                                                 long durationMs, int round) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("toolName", call.name());
        entry.put("arguments", args);
        entry.put("durationMs", durationMs);
        entry.put("round", round);
        entry.put("ok", !toolResult.containsKey("error"));
        return entry;
    }

    private ExecutorResult buildResult(String outputKey, String content, int totalTokens,
                                       List<Map<String, Object>> toolCallLog, List<RetrievedChunk> references) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(outputKey, content);
        output.put("tool_calls", toolCallLog);
        if (references != null) {
            output.put("references", ragService.toReferences(references));
        }
        return new ExecutorResult(output, totalTokens);
    }

    // ---------------- DeepSeek 流式调用与解析 ----------------

    /**
     * 发起一次流式 chat 请求并解析：content 增量、tool_calls 分片、usage、finish_reason。
     * pushDeltas=true 时 content 增量实时推 sink（单轮路径）；false 时缓冲在 contentDeltas（FC 路径轮末判断后重放）。
     */
    private ChatRound callChatStream(String model, Object temperature,
                                     List<Map<String, Object>> messages,
                                     List<Map<String, Object>> toolsParam,
                                     boolean pushDeltas, java.util.function.Consumer<String> sink) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        if (temperature instanceof Number number) {
            body.put("temperature", number.doubleValue());
        }
        if (toolsParam != null && !toolsParam.isEmpty()) {
            body.put("tools", toolsParam);
            body.put("tool_choice", "auto");
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
            return readStream(response, pushDeltas, sink);
        } catch (IOException e) {
            throw new IllegalStateException("DeepSeek 调用异常（60s 超时或网络错误）: " + e.getMessage(), e);
        }
    }

    /** 解析 SSE 流：按行缓冲避免分片截断，data: [DONE] 结束。 */
    private ChatRound readStream(Response response, boolean pushDeltas, java.util.function.Consumer<String> sink)
            throws IOException {
        StringBuilder content = new StringBuilder();
        List<String> contentDeltas = new ArrayList<>();
        Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();
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
                JsonNode choice = chunk.path("choices").path(0);

                JsonNode delta = choice.path("delta").path("content");
                if (delta.isTextual() && !delta.asText().isEmpty()) {
                    content.append(delta.asText());
                    if (pushDeltas) {
                        sink.accept(delta.asText());
                    } else {
                        contentDeltas.add(delta.asText());
                    }
                }

                // tool_calls 分片：index 区分并行调用，name 首片携带，arguments 逐片拼接
                JsonNode toolCallDeltas = choice.path("delta").path("tool_calls");
                if (toolCallDeltas.isArray()) {
                    for (JsonNode tc : toolCallDeltas) {
                        ToolCallBuilder builder = toolCallBuilders
                                .computeIfAbsent(tc.path("index").asInt(), ToolCallBuilder::new);
                        if (tc.hasNonNull("id")) {
                            builder.id = tc.get("id").asText();
                        }
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name")) {
                            builder.name = fn.get("name").asText();
                        }
                        if (fn.hasNonNull("arguments")) {
                            builder.arguments.append(fn.get("arguments").asText());
                        }
                    }
                }

                JsonNode usage = chunk.path("usage");
                if (usage.isObject() && usage.hasNonNull("total_tokens")) {
                    totalTokens = usage.get("total_tokens").asInt();
                }
            }
        }

        List<ToolCall> toolCalls = toolCallBuilders.values().stream()
                .map(ToolCallBuilder::build)
                .toList();
        return new ChatRound(content.toString(), contentDeltas, toolCalls,
                totalTokens == null ? 0 : totalTokens);
    }

    // ---------------- tools 参数构造 ----------------

    private List<Tool> loadTools(Object toolsConfig) {
        if (!(toolsConfig instanceof List<?> ids) || ids.isEmpty()) {
            return List.of();
        }
        List<Long> toolIds = ids.stream()
                .filter(Number.class::isInstance)
                .map(v -> ((Number) v).longValue())
                .toList();
        if (toolIds.isEmpty()) {
            return List.of();
        }
        return toolMapper.selectBatchIds(toolIds).stream()
                .filter(t -> t.getEnabled() != null && t.getEnabled() == 1)
                .toList();
    }

    private int maxToolRounds(Object configValue) {
        if (configValue instanceof Number number && number.intValue() >= 1) {
            return Math.min(number.intValue(), 10);
        }
        return DEFAULT_MAX_TOOL_ROUNDS;
    }

    /** OpenAI 兼容 tools 参数：params_schema（JSON 数组）→ JSON Schema object。 */
    List<Map<String, Object>> buildToolsParam(List<Tool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription() == null ? "" : tool.getDescription());
            function.put("parameters", toFunctionParameters(tool.getParamsSchema()));
            result.add(Map.of("type", "function", "function", function));
        }
        return result;
    }

    private Map<String, Object> toFunctionParameters(String paramsSchemaJson) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (paramsSchemaJson != null && !paramsSchemaJson.isBlank()) {
            try {
                JsonNode fields = objectMapper.readTree(paramsSchemaJson);
                if (fields.isArray()) {
                    for (JsonNode field : fields) {
                        String key = field.path("key").asText();
                        if (key.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> property = new LinkedHashMap<>();
                        property.put("type", "number".equals(field.path("type").asText()) ? "number" : "string");
                        property.put("description", field.path("label").asText(""));
                        properties.put(key, property);
                        if (field.path("required").asBoolean(false)) {
                            required.add(key);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("params_schema 解析失败，按无参工具处理: {}", e.getMessage());
            }
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }
        return parameters;
    }

    private Map<String, Object> assistantToolCallMessage(List<ToolCall> toolCalls) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            calls.add(Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.arguments())));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "");
        message.put("tool_calls", calls);
        return message;
    }

    private Map<String, Object> toolResultMessage(String toolCallId, Map<String, Object> toolResult) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", toJson(toolResult));
        return message;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (IOException e) {
            return Map.of("_rawArguments", argumentsJson);
        }
    }

    // ---------------- 内部模型 ----------------

    /** 一轮流式调用的解析结果。 */
    private record ChatRound(String content, List<String> contentDeltas,
                             List<ToolCall> toolCalls, int tokens) {
    }

    record ToolCall(String id, String name, String arguments) {
    }

    private static class ToolCallBuilder {
        final int index;
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();

        ToolCallBuilder(int index) {
            this.index = index;
        }

        ToolCall build() {
            return new ToolCall(id, name, arguments.toString());
        }
    }

    // ---------------- 工具方法 ----------------

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
