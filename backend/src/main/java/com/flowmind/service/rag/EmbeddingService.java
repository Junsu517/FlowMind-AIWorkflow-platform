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

/**
 * SiliconFlow 向量化（OpenAI 兼容 /embeddings，model=BAAI/bge-m3，1024 维）。
 * 分批提交（每批 16 条），失败统一抛 IllegalStateException，由调用方映射 EMBEDDING_ERROR。
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int BATCH_SIZE = 16;

    @Value("${embedding.base-url}")
    private String baseUrl;

    @Value("${embedding.api-key}")
    private String apiKey;

    @Value("${embedding.model}")
    private String model;

    private final ObjectMapper objectMapper;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(60))
            .build();

    public EmbeddingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 单条文本向量化（检索 query 用）。 */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** 批量向量化，返回顺序与输入一致。 */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            all.addAll(call(texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()))));
        }
        return all;
    }

    private List<float[]> call(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("EMBEDDING_API_KEY 未配置，无法向量化");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);

        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(writeJson(body), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() == null ? "" : response.body().string();
                throw new IllegalStateException("向量化服务调用失败 HTTP " + response.code()
                        + ": " + err.substring(0, Math.min(err.length(), 200)));
            }
            JsonNode data = objectMapper.readTree(response.body().string()).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("向量化响应条数不匹配: 期望 " + texts.size());
            }
            List<float[]> result = new ArrayList<>(texts.size());
            for (JsonNode item : data) {
                JsonNode vector = item.path("embedding");
                float[] embedding = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    embedding[i] = (float) vector.get(i).asDouble();
                }
                result.add(embedding);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("向量化服务不可达: " + e.getMessage(), e);
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
