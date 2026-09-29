package com.flowmind.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 管线编排：入库（切分→向量化→Chroma）与检索（向量化→Top-K）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    static final int TOP_K = 3;
    static final int SNIPPET_MAX = 80;
    private static final String SYSTEM_PROMPT_PREFIX =
            "以下是参考资料，请仅依据资料回答，资料未涉及的内容请说明\"资料中未提及\"：\n";

    private final EmbeddingService embeddingService;
    private final ChromaStoreService chromaStoreService;

    /** 切分 → 批量向量化 → 入库，返回 chunk 数。失败抛 IllegalStateException（调用方映射 EMBEDDING_ERROR）。 */
    public int ingest(Long kbId, Long docId, String filename, String text) {
        List<String> chunks = TextSplitter.split(text);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档内容为空或无法提取文本");
        }
        List<float[]> vectors = embeddingService.embedBatch(chunks);
        chromaStoreService.addChunks(kbId, docId, filename, chunks, vectors);
        log.info("文档入库完成: kbId={}, docId={}, chunks={}", kbId, docId, chunks.size());
        return chunks.size();
    }

    /** 渲染后的 prompt 作为检索 query（方案 §3：MVP 简化）。 */
    public List<RetrievedChunk> retrieve(Long kbId, String query) {
        float[] vector = embeddingService.embed(query);
        return chromaStoreService.query(kbId, vector, TOP_K);
    }

    /** 输出用的 references：[{filename, snippet(≤80字), score}]。 */
    public List<Map<String, Object>> toReferences(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("filename", chunk.filename());
            ref.put("snippet", snippet(chunk.text()));
            ref.put("score", Math.round(chunk.score() * 10000.0) / 10000.0);
            references.add(ref);
        }
        return references;
    }

    /** 注入 messages 的 system 内容（完整 chunk 进资料，snippet 截断仅用于 references 展示）。 */
    public String buildSystemPrompt(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_PREFIX);
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("【资料 ").append(i + 1).append("】")
                    .append(chunks.get(i).text()).append('\n');
        }
        return sb.toString();
    }

    private String snippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_MAX ? normalized
                : normalized.substring(0, SNIPPET_MAX) + "…";
    }
}
