package com.interview.assistant.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class QdrantConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
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

                if (statusCode == 429 || statusCode == 500 || statusCode == 520) {
                    // Rate limit or server error - retry
                    log.warn("[QdrantConfig] HTTP error ({}): {}, attempt {}/{}", 
                            statusCode, output.substring(0, Math.min(150, output.length())), 
                            attempt, maxRetries);
                    if (attempt < maxRetries) {
                        Thread.sleep(3000);
                        continue;
                    }
                    return null;
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
                log.info("[QdrantConfig] API响应前80字: {}", content != null ? content.substring(0, Math.min(80, content.length())) : "null");
                return content;

            } catch (IOException e) {
                log.warn("[QdrantConfig] HTTP IO异常(attempt {}/{}): {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    continue;
                }
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[QdrantConfig] 线程中断", e);
                return null;
            }
        }

        return null;
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
     *
     * MiniMax 响应格式:
     * {"id":"...","choices":[{"index":0,"message":{"role":"assistant","content":"实际回答"},"finish_reason":"stop"}],"usage":{...}}
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
            // Fallback: return first 200 chars
            return text.substring(0, Math.min(200, text.length()));
        } catch (Exception e) {
            // JSON parse failed, return trimmed text
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

    private String escapeShell(String s) {
        return s.replace("'", "'\\''");
    }
}
