package com.flowmind.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chroma 向量库访问（REST v2，OkHttp 直连——退路说明见任务汇报）。
 * 每知识库一个 collection（kb_{id}）；v2 的 add/query 需要 collection UUID，
 * 用 get_or_create 幂等获取并按 kbId 缓存。
 */
@Slf4j
@Service
public class ChromaStoreService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String COLLECTIONS_PATH =
            "/api/v2/tenants/default_tenant/databases/default_database/collections";

    @Value("${chroma.base-url}")
    private String baseUrl;

    private final ObjectMapper objectMapper;
    private final Map<Long, String> collectionIdCache = new ConcurrentHashMap<>();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    public ChromaStoreService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** get_or_create 拿 collection UUID（幂等，按 kbId 缓存）；显式 cosine 距离（默认 l2，与 score 换算设计不符）。Chroma 不可达时抛 IllegalStateException。 */
    public String ensureCollection(Long kbId) {
        return collectionIdCache.computeIfAbsent(kbId, id -> {
            Map<String, Object> body = Map.of(
                    "name", collectionName(id),
                    "get_or_create", true,
                    "configuration", Map.of("hnsw", Map.of("space", "cosine")));
            JsonNode created = execute(post(COLLECTIONS_PATH, body));
            return created.path("id").asText(null);
        });
    }

    public void deleteCollection(Long kbId) {
        collectionIdCache.remove(kbId);
        try {
            // 实测 Chroma v2 的 DELETE 支持 collection 名字（GET 只认 UUID），按名删幂等
            execute(delete(COLLECTIONS_PATH + "/" + collectionName(kbId)));
        } catch (IllegalStateException e) {
            log.info("collection 不存在，跳过删除: kb_{}", kbId);
        }
    }

    /** 写入一批 chunk。metadata 记 kb_id/doc_id/filename/chunk_index 供过滤与展示。 */
    public void addChunks(Long kbId, Long docId, String filename, List<String> chunks, List<float[]> vectors) {
        String collectionId = ensureCollection(kbId);
        List<String> ids = new ArrayList<>(chunks.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(chunks.size());
        List<double[]> embeddings = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ids.add("doc_" + docId + "_chunk_" + i);
            metadatas.add(Map.of(
                    "kb_id", kbId, "doc_id", docId,
                    "filename", filename, "chunk_index", i));
            float[] v = vectors.get(i);
            double[] d = new double[v.length];
            for (int j = 0; j < v.length; j++) {
                d[j] = v[j];
            }
            embeddings.add(d);
        }
        Map<String, Object> body = Map.of(
                "ids", ids, "embeddings", embeddings, "documents", chunks, "metadatas", metadatas);
        execute(post(COLLECTIONS_PATH + "/" + collectionId + "/add", body));
    }

    /** 删除某文档的全部 chunk（按 doc_id 过滤）。 */
    public void deleteChunksByDoc(Long kbId, Long docId) {
        String collectionId = ensureCollection(kbId);
        execute(post(COLLECTIONS_PATH + "/" + collectionId + "/delete",
                Map.of("where", Map.of("doc_id", docId))));
    }

    /** Top-K 检索。Chroma 返回 cosine distance（越小越近）。 */
    public List<RetrievedChunk> query(Long kbId, float[] queryVector, int topK) {
        String collectionId = ensureCollection(kbId);
        double[] d = new double[queryVector.length];
        for (int i = 0; i < queryVector.length; i++) {
            d[i] = queryVector[i];
        }
        Map<String, Object> body = Map.of(
                "query_embeddings", List.of(d),
                "n_results", topK,
                "where", Map.of("kb_id", kbId),
                "include", List.of("documents", "metadatas", "distances"));
        JsonNode result = execute(post(COLLECTIONS_PATH + "/" + collectionId + "/query", body));

        JsonNode documents = result.path("documents").path(0);
        JsonNode metadatas = result.path("metadatas").path(0);
        JsonNode distances = result.path("distances").path(0);
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            String filename = metadatas.path(i).path("filename").asText("unknown");
            chunks.add(new RetrievedChunk(filename, documents.get(i).asText(),
                    distances.path(i).asDouble(1.0)));
        }
        return chunks;
    }

    public static String collectionName(Long kbId) {
        return "kb_" + kbId;
    }

    // ---------------- HTTP 基建 ----------------

    private Request post(String path, Object body) {
        return new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(writeJson(body), JSON))
                .build();
    }

    private Request get(String path) {
        return new Request.Builder().url(baseUrl + path).get().build();
    }

    private Request delete(String path) {
        return new Request.Builder().url(baseUrl + path).delete().build();
    }

    private JsonNode execute(Request request) {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("向量库调用失败 HTTP " + response.code()
                        + ": " + body.substring(0, Math.min(body.length(), 200)));
            }
            return body.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("向量库不可达: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
    }
}
