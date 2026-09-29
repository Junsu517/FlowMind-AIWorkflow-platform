package com.flowmind.service.rag;

/** 检索命中的 chunk（Chroma query 结果的领域对象） */
public record RetrievedChunk(String filename, String text, double distance) {

    /** 相似度分：Chroma 返回 cosine distance（越小越近），转成越大越相关的分值便于展示 */
    public double score() {
        return 1.0 / (1.0 + distance);
    }
}
