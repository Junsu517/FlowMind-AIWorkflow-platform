package com.flowmind.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAG prompt 拼装与 references 输出格式（不依赖网络，Embedding/Chroma 传 null）。 */
class RagServiceTest {

    private final RagService ragService = new RagService(null, null);

    @Test
    void systemPromptInjectsFullChunks() {
        String prompt = ragService.buildSystemPrompt(List.of(
                new RetrievedChunk("guide.txt", "布偶猫每日喂食三次。", 0.1),
                new RetrievedChunk("guide2.txt", "英短每日喂食两次。", 0.2)));

        assertTrue(prompt.startsWith("以下是参考资料，请仅依据资料回答，资料未涉及的内容请说明\"资料中未提及\"："));
        assertTrue(prompt.contains("【资料 1】布偶猫每日喂食三次。"));
        assertTrue(prompt.contains("【资料 2】英短每日喂食两次。"));
    }

    @Test
    void referencesSnippetTruncatedTo80() {
        String longText = "猫粮选择要点".repeat(50); // 300 字
        List<Map<String, Object>> refs = ragService.toReferences(List.of(
                new RetrievedChunk("cat-food.md", longText, 0.15)));

        assertEquals(1, refs.size());
        Map<String, Object> ref = refs.get(0);
        assertEquals("cat-food.md", ref.get("filename"));
        String snippet = (String) ref.get("snippet");
        assertTrue(snippet.length() <= 81, "snippet ≤80 字（含省略号）: " + snippet.length());
        assertTrue(snippet.endsWith("…"));
        double score = (Double) ref.get("score");
        assertTrue(score > 0.5 && score < 1.0, "cosine distance 0.15 → score≈0.87: " + score);
    }

    @Test
    void scoreMonotonic() {
        RetrievedChunk near = new RetrievedChunk("a", "t", 0.05);
        RetrievedChunk far = new RetrievedChunk("b", "t", 0.9);
        assertTrue(near.score() > far.score(), "distance 越小 score 越大");
    }
}
