package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * BGE 本地嵌入服务客户端
 *
 * 调用本地 Python FastAPI 服务的 HTTP 接口，
 * 底层使用 bge-small-zh-v1.5 模型（233MB，中文语义向量）。
 *
 * 服务地址：http://localhost:8001
 * 接口路径：POST /v1/embeddings  或  POST /embed
 *
 * 请求体：{"texts": ["文本1", "文本2"]}   或   {"texts": "单条文本"}
 * 响应体：{"model":"BAAI/bge-small-zh-v1.5","dim":512,"embeddings":[[0.1,...], [0.2,...]]}
 */
@Slf4j
@Service
public class BgeEmbeddingService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    /** 缓存向量维度（启动时探测一次） */
    private volatile Integer cachedDimension = null;

    public BgeEmbeddingService(
            @Value("${spring.ai.embedding.bge.base-url:http://localhost:8001}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("[BgeEmbeddingService] 初始化完成，baseUrl={}", baseUrl);
    }

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量（float[]），失败时返回 null
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            String requestBody = objectMapper.writeValueAsString(
                    new BgeRequest(List.of(text)));

            String responseBody = restClient.post()
                    .uri("/v1/embeddings")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return parseFirstEmbedding(responseBody);

        } catch (Exception e) {
            log.warn("[BgeEmbeddingService] 向量化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 批量将多个文本转换为向量
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> results = new ArrayList<>(texts.size());
        for (String t : texts) {
            results.add(embed(t));
        }
        return results;
    }

    /**
     * 获取向量维度（从响应中提取，启动探测一次后缓存）
     */
    public int getDimension() {
        Integer cached = cachedDimension;
        if (cached != null) {
            return cached;
        }

        try {
            String requestBody = objectMapper.writeValueAsString(
                    new BgeRequest(List.of("dim-check")));
            String responseBody = restClient.post()
                    .uri("/v1/embeddings")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            int dim = root.path("dim").asInt(0);
            if (dim > 0) {
                cachedDimension = dim;
                log.info("[BgeEmbeddingService] 向量维度探测成功: dim={}", dim);
                return dim;
            }
        } catch (Exception e) {
            log.warn("[BgeEmbeddingService] 维度探测失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 判断 BGE 服务是否可用
     */
    public boolean isAvailable() {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    new BgeRequest(List.of("health")));
            String responseBody = restClient.post()
                    .uri("/v1/embeddings")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            return root.has("embeddings") && root.get("embeddings").isArray();
        } catch (Exception e) {
            log.warn("[BgeEmbeddingService] 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析响应，返回第一个 embedding 向量
     */
    private float[] parseFirstEmbedding(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode embeddingsNode = root.path("embeddings");

        if (embeddingsNode.isMissingNode() || !embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
            log.warn("[BgeEmbeddingService] embeddings 字段缺失或为空，response={}",
                    responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody);
            return null;
        }

        JsonNode first = embeddingsNode.get(0);
        int size = first.size();
        float[] vector = new float[size];
        for (int i = 0; i < size; i++) {
            vector[i] = (float) first.get(i).asDouble();
        }
        return vector;
    }

    /**
     * BGE API 请求体
     */
    private record BgeRequest(String model, List<String> texts, boolean normalize) {
        public BgeRequest(List<String> texts) {
            this("BAAI/bge-small-zh-v1.5", texts, true);
        }
    }
}
