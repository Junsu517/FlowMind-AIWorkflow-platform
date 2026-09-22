package com.flowmind.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void 渲染简单引用() {
        String out = renderer.render("给 {{input.city}} 的穿衣建议", Map.of("input", Map.of("city", "南昌")));
        assertEquals("给 南昌 的穿衣建议", out);
    }

    @Test
    void 渲染嵌套字段() {
        String out = renderer.render("温度 {{http_1.data.temp}} 度",
                Map.of("http_1", Map.of("data", Map.of("temp", 26))));
        assertEquals("温度 26 度", out);
    }

    @Test
    void 多个相同引用全部替换() {
        String out = renderer.render("{{a.x}}+{{a.x}}", Map.of("a", Map.of("x", "1")));
        assertEquals("1+1", out);
    }

    @Test
    void 引用不存在抛TemplateException() {
        assertThrows(TemplateException.class,
                () -> renderer.render("{{input.city}}", Map.of("input", Map.of())));
    }

    @Test
    void 渲染Map递归处理字符串值() {
        Map<String, Object> config = Map.of(
                "prompt", "你好 {{input.name}}",
                "params", Map.of("city", "{{input.city}}"),
                "nested", Map.of("deep", "{{a.b}}"));
        Map<String, Object> out = renderer.renderMap(config, Map.of(
                "input", Map.of("name", "小明", "city", "北京"),
                "a", Map.of("b", "deep")));
        assertEquals("你好 小明", out.get("prompt"));
        assertEquals("北京", ((Map<?, ?>) out.get("params")).get("city"));
        assertEquals("deep", ((Map<?, ?>) out.get("nested")).get("deep"));
    }
}
