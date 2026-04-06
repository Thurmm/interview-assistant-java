package com.interview.assistant.service;

import com.interview.assistant.config.QdrantConfig;
import com.interview.assistant.model.AppSettings.ModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * LLM 服务（直接使用 OkHttp 调用 MiniMax API）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final QdrantConfig qdrantConfig;

    public record LlmResult(String content, String error) {}

    public LlmResult callLlm(
            List<Map<String, String>> messages,
            ModelConfig config,
            Double temperature,
            Integer maxTokens
    ) {
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return new LlmResult(null, "API Key 未配置");
        }

        String result = qdrantConfig.callLlm(
                messages,
                config.getApiKey(),
                config.getBaseUrl(),
                config.getModel(),
                temperature
        );

        if (result == null) {
            return new LlmResult(null, "LLM 调用失败，请检查网络和 API Key");
        }

        return new LlmResult(result, null);
    }

    public LlmResult callLlm(List<Map<String, String>> messages, ModelConfig config) {
        return callLlm(messages, config, 0.7, null);
    }

    public boolean testConnection(ModelConfig config) {
        LlmResult result = callLlm(
                List.of(Map.of(
                        "role", "user",
                        "content", "请回复'连接成功'，只需要这三个字。"
                )),
                config, 0.0, 100
        );

        if (result.error() != null) return false;
        String content = result.content();
        return content != null && (
                content.contains("成功") ||
                content.toLowerCase().contains("success") ||
                content.contains("连接成功")
        );
    }
}
