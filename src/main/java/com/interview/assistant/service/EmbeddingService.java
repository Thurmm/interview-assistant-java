package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化服务
 *
 * 支持两种模式：
 * 1. Ollama 本地模式（provider=bge）：调用本地 Ollama 服务
 *    - POST /api/embeddings
 *    - Body: {"model": "bge-small-zh-v1.5", "prompt": "text"}
 * 2. MiniMax 在线 API（provider=minimax）：调用 MiniMax Embedding API
 *    - POST /v1/embeddings
 *    - Body: {"model": "embedding-2", "input": ["text"]}
 */
@Slf4j
@Service
public class EmbeddingService {

    private final RestClient embeddingRestClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModel;
    private final String provider;

    public EmbeddingService(
            RestClient embeddingRestClient,
            ObjectMapper objectMapper,
            @Value("${spring.ai.embedding.provider:bge}") String provider,
            @Value("${spring.ai.embedding.bge.model:bge-small-zh-v1.5}") String bgeModel,
            @Value("${spring.ai.embedding.minimax.model:embedding-2}") String minimaxModel
    ) {
        this.embeddingRestClient = embeddingRestClient;
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.embeddingModel = "minimax".equalsIgnoreCase(provider) ? minimaxModel : bgeModel;
        log.info("[EmbeddingService] 初始化完成，provider={}, model={}", provider, embeddingModel);
    }

    public static class EmbeddingException extends RuntimeException {
        private final String errorCode;
        private final String detail;

        public EmbeddingException(String errorCode, String detail) {
            super(errorCode + ": " + detail);
            this.errorCode = errorCode;
            this.detail = detail;
        }

        public String getErrorCode() { return errorCode; }
        public String getDetail() { return detail; }

        public static EmbeddingException insufficientBalance(String detail) {
            return new EmbeddingException("INSUFFICIENT_BALANCE", detail);
        }

        public static EmbeddingException apiKeyMissing() {
            return new EmbeddingException("API_KEY_MISSING", "Embedding API Key 未配置");
        }

        public static EmbeddingException ollamaNotRunning() {
            return new EmbeddingException("OLLAMA_NOT_RUNNING", "Ollama 服务未启动，请运行: ollama pull bge-small-zh-v1.5 && ollama serve");
        }
    }

    public float[] embed(String text) throws EmbeddingException {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            if ("minimax".equalsIgnoreCase(provider)) {
                return embedMiniMax(text);
            } else {
                return embedOllama(text);
            }
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[EmbeddingService] 向量化失败: {}，text={}", e.getMessage(),
                    text.substring(0, Math.min(50, text.length())));
            throw new EmbeddingException("UNKNOWN", e.getMessage());
        }
    }

    private float[] embedOllama(String text) throws EmbeddingException {
        try {
            String jsonBody = objectMapper.writeValueAsString(
                    new OllamaEmbeddingRequest(embeddingModel, text));

            String responseBody = embeddingRestClient.post()
                    .uri("/api/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("error")) {
                String errorMsg = root.get("error").asText();
                if (errorMsg.contains("model")) {
                    throw EmbeddingException.ollamaNotRunning();
                }
                throw new EmbeddingException("OLLAMA_ERROR", errorMsg);
            }

            JsonNode embeddingNode = root.path("embedding");
            if (embeddingNode.isMissingNode()) {
                log.warn("[EmbeddingService] embedding 字段缺失，response={}", responseBody);
                throw new EmbeddingException("INVALID_RESPONSE", "embedding 字段缺失");
            }

            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }

            log.debug("[EmbeddingService/Ollama] text → dim={}", vector.length);
            return vector;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                throw EmbeddingException.ollamaNotRunning();
            }
            throw new EmbeddingException("UNKNOWN", e.getMessage());
        }
    }

    private float[] embedMiniMax(String text) throws EmbeddingException {
        try {
            String jsonBody = objectMapper.writeValueAsString(
                    new MiniMaxEmbeddingRequest(embeddingModel, List.of(text)));

            String responseBody = embeddingRestClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode baseResp = root.path("base_resp");
            if (baseResp.has("status_code")) {
                int code = baseResp.get("status_code").asInt();
                String msg = baseResp.has("status_msg") ? baseResp.get("status_msg").asText() : "";

                if (code == 1008) {
                    throw EmbeddingException.insufficientBalance(msg);
                }
                if (code == 1001 || code == 2013) {
                    throw new EmbeddingException("API_ERROR", msg);
                }
            }

            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (embeddingNode.isMissingNode()) {
                log.warn("[EmbeddingService] embedding 字段缺失，response={}", responseBody);
                throw new EmbeddingException("INVALID_RESPONSE", "embedding 字段缺失: " + responseBody);
            }

            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }

            log.debug("[EmbeddingService/MiniMax] text → dim={}", vector.length);
            return vector;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("UNKNOWN", e.getMessage());
        }
    }

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

    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0f;
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0) {
            return 0f;
        }

        return (float) (dotProduct / denominator);
    }

    public int getDimension() {
        float[] test = embed("dimension-check");
        return test != null ? test.length : 0;
    }

    public boolean isAvailable() {
        try {
            float[] test = embed("health check");
            return test != null && test.length > 0;
        } catch (Exception e) {
            log.warn("[EmbeddingService] embedding 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return embeddingModel;
    }

    private record OllamaEmbeddingRequest(String model, String prompt) {}
    private record MiniMaxEmbeddingRequest(String model, List<String> input) {}
}
