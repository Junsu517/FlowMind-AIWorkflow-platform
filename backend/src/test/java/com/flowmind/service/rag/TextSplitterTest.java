package com.flowmind.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 切分逻辑：目标 ~500 字/chunk、相邻重叠 ~50 字（方案 §2 参数）。 */
class TextSplitterTest {

    private String paragraph(int length) {
        return "字".repeat(length);
    }

    @Test
    void shortTextSingleChunk() {
        List<String> chunks = TextSplitter.split("养猫指南。\n\n每日喂食两次。");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("养猫指南"));
        assertTrue(chunks.get(0).contains("每日喂食两次"));
    }

    @Test
    void longParagraphHardSplitWithOverlap() {
        String text = paragraph(1200);
        List<String> chunks = TextSplitter.split(text);
        // 步进 450：ceil(1200/450) = 3
        assertEquals(3, chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 500, "chunk 不得超目标长度: " + chunk.length());
        }
        // 相邻重叠：chunk[i] 的尾部 50 字 == chunk[i+1] 的头部 50 字
        assertEquals(chunks.get(0).substring(chunks.get(0).length() - 50),
                chunks.get(1).substring(0, 50), "硬切相邻 chunk 应有 50 字重叠");
    }

    @Test
    void paragraphsMergedToTarget() {
        String text = String.join("\n\n",
                paragraph(200), "第一段主题词", paragraph(200), "第二段主题词", paragraph(200));
        List<String> chunks = TextSplitter.split(text);
        assertTrue(chunks.size() >= 2, "620+ 字内容应切多块: " + chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 500 + 60),
                "合并块不显著超过目标");
    }

    @Test
    void crlfAndBlankNormalized() {
        List<String> chunks = TextSplitter.split("第一段\r\n\r\n\r\n第二段\r\n");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段") && chunks.get(0).contains("第二段"));
    }

    @Test
    void emptySafe() {
        assertEquals(0, TextSplitter.split(null).size());
        assertEquals(0, TextSplitter.split("  \n\n  ").size());
    }
}
