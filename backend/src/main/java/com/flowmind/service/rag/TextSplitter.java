package com.flowmind.service.rag;

/**
 * 文档切分：按段落合并到目标长度，超长段落硬切，相邻 chunk 保持重叠窗口。
 * 参数来自阶梯 5 技术方案：目标 ~500 字/chunk、相邻重叠 ~50 字。
 */
public final class TextSplitter {

    static final int TARGET_CHUNK_SIZE = 500;
    static final int OVERLAP = 50;

    private TextSplitter() {
    }

    public static java.util.List<String> split(String text) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim();
        String[] paragraphs = normalized.split("\\n{2,}");

        StringBuilder current = new StringBuilder();
        String overlapTail = "";
        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > TARGET_CHUNK_SIZE) {
                // 超长段落：先落当前缓冲，再按步进硬切（步进 = 目标 - 重叠）
                flush(chunks, current);
                overlapTail = "";
                for (int i = 0; i < p.length(); i += TARGET_CHUNK_SIZE - OVERLAP) {
                    chunks.add(p.substring(i, Math.min(i + TARGET_CHUNK_SIZE, p.length())));
                }
                continue;
            }
            if (current.length() + p.length() + 2 > TARGET_CHUNK_SIZE && current.length() > 0) {
                overlapTail = tail(current.toString(), OVERLAP);
                flush(chunks, current);
                current.append(overlapTail).append("\n\n");
            }
            current.append(p).append("\n\n");
        }
        flush(chunks, current);
        return chunks.stream().filter(c -> !c.isBlank()).toList();
    }

    private static void flush(java.util.List<String> chunks, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            chunks.add(trimmed);
        }
        current.setLength(0);
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
