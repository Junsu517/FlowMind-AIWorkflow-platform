package com.flowmind.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {{ref}} 模板渲染：对字符串和 Map 结构递归渲染，引用不存在的 key 抛 TemplateException。 */
public class TemplateRenderer {

    private static final Pattern REF = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    public String render(String template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        Matcher m = REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            Object value = resolve(ref, context);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> renderMap(Map<String, Object> map, Map<String, Object> context) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            out.put(e.getKey(), renderValue(e.getValue(), context));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object renderValue(Object value, Map<String, Object> context) {
        if (value instanceof String s) {
            return render(s, context);
        }
        if (value instanceof Map<?, ?> m) {
            return renderMap((Map<String, Object>) m, context);
        }
        if (value instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>(l.size());
            for (Object item : l) {
                out.add(renderValue(item, context));
            }
            return out;
        }
        return value;
    }

    /** 解析引用：nodeKey.field；嵌套 field 用 . 逐层取值，如 http_1.data.temp。 */
    public Object resolve(String ref, Map<String, Object> context) {
        String[] parts = ref.split("\\.");
        Object cur = context;
        for (String part : parts) {
            if (!(cur instanceof Map<?, ?> map)) {
                throw new TemplateException("引用不存在: " + ref);
            }
            cur = map.get(part);
            if (cur == null) {
                throw new TemplateException("引用不存在: " + ref);
            }
        }
        return cur;
    }
}
