package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.model.AppSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final ObjectMapper objectMapper;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * LLM 调用结果（含原始返回 + 清洗后内容）
     */
    @lombok.Data
    public static class LlmResult {
        private final String rawResponse;
        private final String content;
        private final boolean success;
        private final String errorMessage; // API error detail
    }

    /**
     * 调用 LLM 并同时返回原始返回和清洗后内容
     */
    public LlmResult callLlmWithRaw(
            List<Map<String, String>> messages,
            AppSettings.ModelConfig modelConfig
    ) {
        String apiKey = modelConfig.getApiKey();
        String baseUrl = modelConfig.getBaseUrl();
        String model = modelConfig.getModel();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API Key 未配置");
        }

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        String requestBody = buildRequestBody(model, messages);

        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("[LlmService] URL={}, model={}, attempt={}/{}", url, model, attempt, maxRetries);

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                String respBody;
                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    respBody = response.body() != null ? response.body().string() : "";

                    // 529 服务过载，触发重试
                    if (response.code() == 529 || response.code() == 502 || response.code() == 503 || response.code() == 504) {
                        log.warn("[LlmService] 请求失败 status={}，{}，准备重试 ({}/{})",
                                response.code(), attempt < maxRetries ? "等待后重试" : "不再重试", attempt, maxRetries);
                        if (attempt < maxRetries) {
                            Thread.sleep(attempt * 2000L); // 2s, 4s, 6s 递增等待
                            continue;
                        }
                    }

                    if (!response.isSuccessful()) {
                        String errMsg = "HTTP " + response.code() + ": " + respBody;
                        log.error("[LlmService] 请求失败: {}", errMsg);
                        return new LlmResult(null, null, false, errMsg);
                    }
                }

                String rawContent = extractContent(respBody);
                String cleanedContent = cleanThinkingContent(rawContent);

                log.info("[LlmService] 模型={}, raw 长度={}, cleaned 长度={}",
                        model, rawContent != null ? rawContent.length() : 0, cleanedContent.length());
                log.info("[LlmService] raw 前300字: {}",
                        rawContent != null ? rawContent.substring(0, Math.min(300, rawContent.length())) : "null");
                log.info("[LlmService] cleaned 前300字: {}",
                        cleanedContent.substring(0, Math.min(300, cleanedContent.length())));

                return new LlmResult(rawContent, cleanedContent, true, null);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("[LlmService] 重试被中断", ie);
                return new LlmResult(null, null, false, "Request interrupted");
            } catch (Exception e) {
                lastException = e;
                log.warn("[LlmService] 调用异常 attempt={}/{}: {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(attempt * 2000L); } catch (InterruptedException sie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }

        String finalError = lastException != null ? lastException.getMessage() : "unknown";
        log.error("[LlmService] 调用失败（已重试{}次）: {}", maxRetries, finalError);
        return new LlmResult(null, null, false, finalError);
    }

    public String callLlm(List<Map<String, String>> messages, AppSettings.ModelConfig modelConfig) {
        LlmResult result = callLlmWithRaw(messages, modelConfig);
        return result.isSuccess() ? result.getContent() : null;
    }

    private String cleanThinkingContent(String rawContent) {
        if (rawContent == null) return "";

        String result = rawContent;
        // 去掉 <begin_of_thought>...</end_of_thought> 块
        result = result.replaceAll("(?s)<begin_of_thought>.*?</end_of_thought>", "");
        // 去掉 [(rating) ... ] 评分类推理块
        result = result.replaceAll("(?s)\\[rationale\\].*?\\[/rationale\\]", "");
        result = result.replaceAll("(?s)\\[rating\\].*?\\[/rating\\]", "");
        // 去掉 0-10 points 评分类文字
        result = result.replaceAll("\\[\\d+(\\.\\d+)?\\s*points?\\]", "");
        result = result.replaceAll("(?m)^.*?rating.*?$", "");
        result = result.replaceAll("(?m)^.*?rationale.*?$", "");
        result = result.replaceAll("(?m)^.*?explanation.*?$", "");

        // 找到第一个 { 或 [ 开始的位置，截取之后的内容
        int jsonStart = -1;
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c == '{' || c == '[') {
                jsonStart = i;
                break;
            }
        }

        if (jsonStart > 0) {
            String possibleJson = result.substring(jsonStart);
            if (possibleJson.trim().startsWith("{")) {
                log.info("[LlmService] JSON 从位置 {} 开始，之前有推理内容被去除", jsonStart);
                return possibleJson.trim();
            }
        }

        return result.trim();
    }

    private String buildRequestBody(String model, List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(model).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            sb.append("{\"role\":\"").append(escapeJson(msg.get("role")))
              .append("\",\"content\":\"").append(escapeJson(msg.get("content"))).append("\"}");
            if (i < messages.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t");
    }

    private String extractContent(String respBody) {
        try {
            JsonNode root = objectMapper.readTree(respBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                log.warn("[LlmService] choices 为空或不存在, resp={}", respBody);
                return null;
            }
            JsonNode msg = choices.get(0).get("message");
            if (msg == null) {
                log.warn("[LlmService] message 不存在, resp={}", respBody);
                return null;
            }
            JsonNode contentNode = msg.get("content");
            if (contentNode == null) {
                log.warn("[LlmService] content 不存在, resp={}", respBody);
                return null;
            }
            return contentNode.asText();
        } catch (Exception e) {
            log.error("[LlmService] 解析响应 JSON 失败: {}, resp={}", e.getMessage(),
                    respBody != null ? respBody.substring(0, Math.min(300, respBody.length())) : "null");
            return null;
        }
    }

    public boolean testConnection(AppSettings.ModelConfig modelConfig) {
        try {
            List<Map<String, String>> testMessages = List.of(
                    Map.of("role", "system", "content", "你是一个测试助手"),
                    Map.of("role", "user", "content", "测试连接，请直接返回'连接成功'，不要加任何推理过程"));
            String result = callLlm(testMessages, modelConfig);
            return result != null && result.contains("连接成功");
        } catch (Exception e) {
            log.error("[LlmService] 连接测试失败: {}", e.getMessage());
            return false;
        }
    }
}
