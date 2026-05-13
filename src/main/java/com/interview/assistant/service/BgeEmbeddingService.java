package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BGE 本地嵌入服务客户端
 *
 * 调用本地 Python FastAPI 服务的 HTTP 接口，
 * 底层使用 bge-small-zh-v1.5 模型（233MB，中文语义向量）。
 */
@Slf4j
@Service
public class BgeEmbeddingService {

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
        log.info("[BgeEmbeddingService] 初始化完成，baseUrl={}", baseUrl);
    }

    /**
     * 将文本转换为向量
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            String json = objectMapper.writeValueAsString(
                    Map.of(
                            "model", "BAAI/bge-small-zh-v1.5",
                            "texts", List.of(text),
                            "normalize", true
                    ));
            String response = post("/v1/embeddings", json);
            return parseFirstEmbedding(response);

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
            String json = objectMapper.writeValueAsString(
                    Map.of(
                            "model", "BAAI/bge-small-zh-v1.5",
                            "texts", List.of("dim-check"),
                            "normalize", true
                    ));
            String response = post("/v1/embeddings", json);

            JsonNode root = objectMapper.readTree(response);
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
            String json = objectMapper.writeValueAsString(
                    Map.of(
                            "model", "BAAI/bge-small-zh-v1.5",
                            "texts", List.of("health"),
                            "normalize", true
                    ));
            String response = post("/v1/embeddings", json);

            JsonNode root = objectMapper.readTree(response);
            return root.has("embeddings") && root.get("embeddings").isArray();
        } catch (Exception e) {
            log.warn("[BgeEmbeddingService] 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 发送 POST 请求
     */
    private String post(String path, String json) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("HTTP " + responseCode + ": " + conn.getResponseMessage());
        }

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
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
}
