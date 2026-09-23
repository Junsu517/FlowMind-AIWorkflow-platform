package com.flowmind.service.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.engine.ExecutorResult;
import com.flowmind.engine.NodeExecutionContext;
import com.flowmind.engine.ExecutionContext;
import com.flowmind.entity.Tool;
import com.flowmind.mapper.ToolMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮数截断的确定性验证：MockWebServer 模拟"每轮都请求工具"的 DeepSeek，
 * 工具调用用 stub invoker 回显参数（真实 v4 模型会识破无限循环指令，无法稳定触发，见 FunctionCallingTest）。
 */
class LlmNodeExecutorTruncationTest {

    private static MockWebServer deepseek;
    private static final AtomicInteger sinkCalls = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        deepseek = new MockWebServer();
        deepseek.start();
    }

    @AfterAll
    static void stop() throws Exception {
        deepseek.shutdown();
    }

    /** 每一轮都返回 tool_calls 的 SSE 流。 */
    private static void enqueueToolCallRound(int tokens) {
        String body = """
                data: {"choices":[{"index":0,"delta":{"reasoning_content":"thinking..."},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_%d","type":"function","function":{"name":"weather_query","arguments":""}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":\\"Nanchang\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":%d,"total_tokens":%d}}

                data: [DONE]
                """.formatted(tokens, tokens, tokens);
        deepseek.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body));
    }

    private LlmNodeExecutor executorWithStubTool(String toolName) {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpToolInvoker stubInvoker = new HttpToolInvoker(objectMapper) {
            @Override
            public Map<String, Object> invoke(Tool tool, Map<String, Object> params) {
                return Map.of("echo", params);
            }
        };
        return new LlmNodeExecutor(stubToolMapper(tool(toolName)), stubInvoker, objectMapper);
    }

    /** JDK 动态代理 stub：selectBatchIds 返回预置工具，规避 JDK 25 下 Mockito 不可用。 */
    @SuppressWarnings("unchecked")
    private static ToolMapper stubToolMapper(Tool tool) {
        return (ToolMapper) Proxy.newProxyInstance(ToolMapper.class.getClassLoader(),
                new Class<?>[]{ToolMapper.class},
                (proxy, method, args) -> "selectBatchIds".equals(method.getName())
                        ? List.of(tool) : null);
    }

    private static Tool tool(String name) {
        Tool tool = new Tool();
        tool.setId(1L);
        tool.setName(name);
        tool.setDescription("stub");
        tool.setMethod("GET");
        tool.setEnabled(1);
        tool.setParamsSchema("[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\",\"required\":true}]");
        return tool;
    }

    @Test
    void truncatesAtMaxToolRoundsWithoutError() {
        int maxRounds = 2;
        // 每轮固定回复 tool_calls（usage 各 100）
        for (int i = 0; i < maxRounds; i++) {
            enqueueToolCallRound(100);
        }

        LlmNodeExecutor executor = executorWithStubTool("weather_query");
        ReflectionTestUtils.setField(executor, "baseUrl", deepseek.url("/v1").toString().replaceAll("/$", ""));
        ReflectionTestUtils.setField(executor, "apiKey", "sk-test");

        Map<String, Object> config = Map.of(
                "prompt", "查询天气",
                "tools", List.of(1),
                "max_tool_rounds", maxRounds,
                "output_key", "text");
        NodeExecutionContext ctx = new NodeExecutionContext("llm_1", "FC", "llm", config,
                new ExecutionContext(), delta -> sinkCalls.incrementAndGet());

        ExecutorResult result = executor.execute(ctx);

        String text = String.valueOf(result.getOutput().get("text"));
        assertTrue(text.contains("截断"), "截断场景应带截断说明: " + text);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) result.getOutput().get("tool_calls");
        assertEquals(maxRounds, toolCalls.size(), "应正好执行 max_tool_rounds 轮");
        assertEquals("weather_query", toolCalls.get(0).get("toolName"));
        assertEquals(Boolean.TRUE, toolCalls.get(0).get("ok"));
        assertEquals("Nanchang",
                ((Map<?, ?>) toolCalls.get(0).get("arguments")).get("city"));

        assertEquals(200, result.getTokens(), "多轮 usage 应求和 (100 x 2)");
        assertEquals(maxRounds, deepseek.getRequestCount(), "应发起 max_tool_rounds 次 LLM 请求");
        assertEquals(0, sinkCalls.get(), "中间轮不向 tokenSink 推送（工具调用轮无最终 content）");
    }

    @Test
    void toolFailureFeedsErrorBackToModelInsteadOfFailingNode() {
        // 第 1 轮 tool_calls，第 2 轮最终文本（模型看到错误后放弃工具）
        deepseek.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"broken_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                        data: {"choices":[],"usage":{"total_tokens":50}}

                        data: [DONE]
                        """));
        deepseek.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"index":0,"delta":{"content":"工"},"finish_reason":null}]}

                        data: {"choices":[{"index":0,"delta":{"content":"具坏了"},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"total_tokens":80}}

                        data: [DONE]
                        """));

        ObjectMapper objectMapper = new ObjectMapper();
        HttpToolInvoker failingInvoker = new HttpToolInvoker(objectMapper) {
            @Override
            public Map<String, Object> invoke(Tool tool, Map<String, Object> params) {
                throw new IllegalStateException("工具调用失败 HTTP 500: broken_tool");
            }
        };
        LlmNodeExecutor executor = new LlmNodeExecutor(stubToolMapper(tool("broken_tool")),
                failingInvoker, objectMapper);
        ReflectionTestUtils.setField(executor, "baseUrl", deepseek.url("/v1").toString().replaceAll("/$", ""));
        ReflectionTestUtils.setField(executor, "apiKey", "sk-test");

        Map<String, Object> config = Map.of("prompt", "x", "tools", List.of(9), "output_key", "text");
        StringBuilder streamed = new StringBuilder();
        NodeExecutionContext ctx = new NodeExecutionContext("llm_1", "FC", "llm", config,
                new ExecutionContext(), streamed::append);

        ExecutorResult result = executor.execute(ctx);

        assertEquals("工具坏了", result.getOutput().get("text"));
        assertEquals(130, result.getTokens());
        assertEquals("工具坏了", streamed.toString(), "最终轮 content 应推送到 tokenSink");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) result.getOutput().get("tool_calls");
        assertEquals(Boolean.FALSE, toolCalls.get(0).get("ok"), "失败工具在日志中标记 ok=false");
    }
}
