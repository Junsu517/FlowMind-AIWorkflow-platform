package com.flowmind.service.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmind.entity.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** params_schema（JSON 数组）→ OpenAI 兼容 tools 参数的转换测试（不依赖 DB/网络）。 */
class LlmNodeExecutorTest {

    private final LlmNodeExecutor executor =
            new LlmNodeExecutor(null, null, null, new ObjectMapper());

    private Tool tool(String paramsSchema) {
        Tool tool = new Tool();
        tool.setId(1L);
        tool.setName("weather_query");
        tool.setDescription("查询指定城市的实时天气情况");
        tool.setParamsSchema(paramsSchema);
        return tool;
    }

    @Test
    void buildsOpenAiCompatibleToolsParam() {
        List<Map<String, Object>> param = executor.buildToolsParam(List.of(tool(
                "[{\"key\":\"city\",\"label\":\"城市\",\"type\":\"text\",\"required\":true}]")));

        assertEquals(1, param.size());
        assertEquals("function", param.get(0).get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) param.get(0).get("function");
        assertEquals("weather_query", function.get("name"));
        assertEquals("查询指定城市的实时天气情况", function.get("description"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertEquals("object", parameters.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) properties.get("city");
        assertEquals("string", city.get("type"));
        assertEquals("城市", city.get("description"));
        assertEquals(List.of("city"), parameters.get("required"));
    }

    @Test
    void numberTypeMapsToJsonNumber() {
        List<Map<String, Object>> param = executor.buildToolsParam(List.of(tool(
                "[{\"key\":\"days\",\"label\":\"天数\",\"type\":\"number\"}]")));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) param.get(0).get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertEquals("number", ((Map<?, ?>) properties.get("days")).get("type"));
        assertEquals(null, parameters.get("required"), "无必填字段时不出 required 键");
    }

    @Test
    void malformedSchemaDegradesToNoParams() {
        List<Map<String, Object>> param = executor.buildToolsParam(List.of(tool("not-json")));

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) param.get(0).get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertEquals("object", parameters.get("type"));
        assertEquals(Map.of(), parameters.get("properties"), "解析失败降级为空属性");
    }
}
