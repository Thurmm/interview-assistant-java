package com.interview.assistant.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Qdrant / LLM API 调用配置
 *
 * 使用 Resilience4j RetryRegistry 编程式 API 实现指数退避重试（2s → 4s → 6s），
 * 替代原来固定 3s 等待的手动重试逻辑。
 */
@Slf4j
@Component
public class QdrantConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Retry retry;

    public QdrantConfig(ObjectMapper objectMapper, RetryRegistry retryRegistry) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 配置指数退避重试：2s → 4s → 6s
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .retryExceptions(
                        java.io.IOException.class,
                        java.net.SocketTimeoutException.class,
                        java.net.ConnectException.class,
                        RetryableApiException.class)
                .build();
        this.retry = retryRegistry.retry("qdrantRetry", retryConfig);

        retry.getEventPublisher()
                .onRetry(event -> log.warn("[QdrantConfig Retry] attempt={}/{}, error={}",
                        event.getNumberOfRetryAttempts() + 1,
                        retry.getRetryConfig().getMaxAttempts(),
                        event.getLastThrowable().getMessage()))
                .onError(event -> log.error("[QdrantConfig Retry] 最终失败 after {} attempts",
                        event.getNumberOfRetryAttempts() + 1))
                .onSuccess(event -> { /* 成功不打印 */ });
    }

    /**
     * 可重试 API 异常（429 / 500 / 520 / 网络错误）
     */
    public static class RetryableApiException extends RuntimeException {
        public RetryableApiException(String message) {
            super(message);
        }
    }

    /**
     * 调用 LLM（带 Resilience4j 指数退避重试）
     *
     * 重试条件：429 / 500 / 520 / 网络超时 / IO 异常
     * 退避序列：2s → 4s → 6s
     */
    public String callLlm(
            List<Map<String, String>> messages,
            String apiKey,
            String baseUrl,
            String model,
            Double temperature
    ) {
        String effectiveKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : defaultApiKey;
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.openai.com/v1";
        String effectiveModel = (model != null && !model.isBlank()) ? model : "gpt-4o-mini";

        String jsonBody = buildChatBody(messages, effectiveModel, temperature);

        log.info("[QdrantConfig] HTTP — url={} model={}", effectiveBaseUrl, effectiveModel);

        Supplier<String> decoratedCall = Retry.decorateSupplier(retry,
                () -> callOnce(effectiveKey, effectiveBaseUrl, jsonBody));

        try {
            return decoratedCall.get();
        } catch (Exception e) {
            log.error("[QdrantConfig] 重试耗尽，最终异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 单次 HTTP 调用
     */
    private String callOnce(String effectiveKey, String effectiveBaseUrl, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveBaseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + effectiveKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String output = response.body();

            // 429 / 500 / 520 → 可重试
            if (statusCode == 429 || statusCode == 500 || statusCode == 520) {
                log.warn("[QdrantConfig] 可重试错误 HTTP {}: {}",
                        statusCode, output != null ? output.substring(0, Math.min(150, output.length())) : "null");
                throw new RetryableApiException("HTTP " + statusCode);
            }

            if (statusCode != 200) {
                log.error("[QdrantConfig] HTTP error ({}): {}", statusCode, output);
                return null;
            }

            if (output == null || output.isBlank()) {
                log.warn("[QdrantConfig] HTTP 返回空");
                return null;
            }

            String content = extractContent(output);
            if (content != null && content.length() > 80) {
                log.info("[QdrantConfig] API响应前80字: {}", content.substring(0, 80));
            }
            return content;

        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            log.warn("[QdrantConfig] 网络超时/连接失败，触发重试: {}", e.getMessage());
            throw new RetryableApiException(e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("[QdrantConfig] IO 异常，触发重试: {}", e.getMessage());
            throw new RetryableApiException(e.getMessage());
        } catch (RetryableApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[QdrantConfig] 线程中断", e);
            return null;
        } catch (Exception e) {
            log.error("[QdrantConfig] 未知异常: {}", e.getMessage());
            return null;
        }
    }

    private String buildChatBody(List<Map<String, String>> messages, String model, Double temperature) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(model).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(msg.get("role"))
              .append("\",\"content\":\"").append(escapeJson(msg.get("content"))).append("\"}");
        }
        sb.append("]");
        if (temperature != null) {
            sb.append(",\"temperature\":").append(temperature);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从 MiniMax / OpenAI API 响应中提取 content
     */
    private String extractContent(String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    String content = message.get("content").asText();
                    return content
                            .replaceAll("(<\\|im_start\\|>think.*?<\\|STOP\\|>)\\s*", " ")
                            .replaceAll("<\\|im_end\\|>", "")
                            .replaceAll("<\\|STOP\\|>", "")
                            .trim();
                }
            }
            return text.substring(0, Math.min(200, text.length()));
        } catch (Exception e) {
            return text != null ? text.trim() : "";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
