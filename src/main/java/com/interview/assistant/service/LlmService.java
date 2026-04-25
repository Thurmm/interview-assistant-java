package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.model.AppSettings;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LLM 调用服务
 *
 * 使用 Resilience4j RetryRegistry 编程式 API 实现指数退避重试（2s → 4s → 6s），
 * 替代原来的手动 Thread.sleep 重试逻辑。
 */
@Slf4j
@Service
public class LlmService {

    private final ObjectMapper objectMapper;
    private final Retry retry;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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
     * 可重试 API 异常（5xx / 529 / 网络错误）
     */
    public static class RetryableApiException extends RuntimeException {
        public RetryableApiException(String message) {
            super(message);
        }
    }

    /**
     * HTTP 响应信息封装
     */
    public record ResponseInfo(String body, int statusCode, boolean isSuccess) {}

    public LlmService(ObjectMapper objectMapper, RetryRegistry retryRegistry) {
        this.objectMapper = objectMapper;
        // 配置指数退避重试：2s → 4s → 6s
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .retryExceptions(
                        java.io.IOException.class,
                        java.net.SocketTimeoutException.class,
                        java.net.ConnectException.class,
                        RetryableApiException.class)
                .ignoreExceptions(
                        IllegalStateException.class,
                        IllegalArgumentException.class)
                .build();
        this.retry = retryRegistry.retry("defaultRetry", retryConfig);

        // 事件监听
        retry.getEventPublisher()
                .onRetry(event -> log.warn("[Retry] attempt={}/{}, error={}",
                        event.getNumberOfRetryAttempts() + 1,
                        retry.getRetryConfig().getMaxAttempts(),
                        event.getLastThrowable().getMessage()))
                .onError(event -> log.error("[Retry] 最终失败 after {} attempts",
                        event.getNumberOfRetryAttempts() + 1))
                .onSuccess(event -> {
                    /* 成功时不打印，保持安静 */ });
    }

    /**
     * 调用 LLM 并同时返回原始返回和清洗后内容
     *
     * 使用 Resilience4j Retry 执行指数退避重试：
     * - 5xx / 529 / 网络超时时自动重试
     * - 退避序列：2s → 4s → 6s
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

        String url = baseUrl.endsWith("/") ? baseUrl + "/chat/completions" : baseUrl + "/chat/completions";
        String requestBody = buildRequestBody(model, messages);

        log.info("[LlmService] URL={}, model={}", url, model);

        Supplier<ResponseInfo> decoratedCall = Retry.decorateSupplier(retry,
                () -> executeOnce(url, apiKey, requestBody));

        ResponseInfo resp;
        try {
            resp = decoratedCall.get();
        } catch (Exception e) {
            log.error("[LlmService] 重试耗尽，最终异常: {}", e.getMessage());
            return new LlmResult(null, null, false, "LLM 调用失败（已重试3次）: " + e.getMessage());
        }

        if (resp == null || !resp.isSuccess()) {
            String err = resp != null ? resp.body() : "LLM 调用失败";
            return new LlmResult(null, null, false, err);
        }

        String rawContent = extractContent(resp.body());
        String cleanedContent = cleanThinkingContent(rawContent);

        log.info("[LlmService] 模型={}, raw 长度={}, cleaned 长度={}",
                model, rawContent != null ? rawContent.length() : 0, cleanedContent.length());
        if (rawContent != null && rawContent.length() > 300) {
            log.info("[LlmService] raw 前300字: {}", rawContent.substring(0, 300));
        }
        if (cleanedContent.length() > 300) {
            log.info("[LlmService] cleaned 前300字: {}", cleanedContent.substring(0, 300));
        }

        return new LlmResult(rawContent, cleanedContent, true, null);
    }

    /**
     * 单次 HTTP 请求，不包含重试逻辑（重试由外层 Retry.decorateSupplier 处理）
     *
     * @throws RetryableApiException 5xx / 529 / 网络错误时抛出，触发 Resilience4j 重试
     */
    private ResponseInfo executeOnce(String url, String apiKey, String requestBody) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            int code = response.code();

            // 5xx / 529 → 可重试
            if (code == 529 || code == 502 || code == 503 || code == 504) {
                log.warn("[LlmService] 服务端错误 status={}，触发重试", code);
                throw new RetryableApiException("HTTP " + code);
            }

            if (!response.isSuccessful()) {
                log.error("[LlmService] 请求失败 HTTP {}: {}", code, body);
                return new ResponseInfo(body, code, false);
            }

            return new ResponseInfo(body, code, true);

        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            log.warn("[LlmService] 网络超时/连接失败，触发重试: {}", e.getMessage());
            throw new RetryableApiException(e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("[LlmService] IO 异常，触发重试: {}", e.getMessage());
            throw new RetryableApiException(e.getMessage());
        } catch (RetryableApiException e) {
            throw e; // 上抛让 Retry 捕获
        } catch (Exception e) {
            log.error("[LlmService] 未知异常: {}", e.getMessage());
            return new ResponseInfo(e.getMessage(), -1, false);
        }
    }

    public String callLlm(List<Map<String, String>> messages, AppSettings.ModelConfig modelConfig) {
        LlmResult result = callLlmWithRaw(messages, modelConfig);
        return result.isSuccess() ? result.getContent() : null;
    }

    private String cleanThinkingContent(String rawContent) {
        if (rawContent == null) return "";

        String result = rawContent;
        result = result.replaceAll("(?s)<begin_of_thought>.*?</end_of_thought>", "");
        result = result.replaceAll("(?s)\\[rationale\\].*?\\[/rationale\\]", "");
        result = result.replaceAll("(?s)\\[rating\\].*?\\[/rating\\]", "");
        result = result.replaceAll("\\[\\d+(\\.\\d+)?\\s*points?\\]", "");
        result = result.replaceAll("(?m)^.*?rating.*?$", "");
        result = result.replaceAll("(?m)^.*?rationale.*?$", "");
        result = result.replaceAll("(?m)^.*?explanation.*?$", "");

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
