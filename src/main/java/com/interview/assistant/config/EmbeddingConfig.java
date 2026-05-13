package com.interview.assistant.config;

import com.interview.assistant.model.AppSettings;
import com.interview.assistant.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring AI Embedding 配置
 *
 * 支持两种模式：
 * 1. Ollama 本地模式（provider=bge）：调用本地 Ollama 服务
 * 2. MiniMax 在线 API（provider=minimax）：调用 MiniMax Embedding API
 *
 * 从 SettingsService 读取运行时配置（API Key + Base URL），
 * 避免在 application.yml 中硬编码凭证。
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    private final SettingsService settingsService;

    @Value("${spring.ai.embedding.provider:bge}")
    private String embeddingProvider;

    @Value("${spring.ai.embedding.bge.base-url:http://localhost:8001}")
    private String bgeBaseUrl;

    @Value("${spring.ai.embedding.bge.model:bge-small-zh-v1.5}")
    private String bgeModel;

    public EmbeddingConfig(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Bean
    public RestClient embeddingRestClient() {
        if ("minimax".equalsIgnoreCase(embeddingProvider)) {
            return buildMiniMaxClient();
        } else {
            return buildOllamaClient();
        }
    }

    private RestClient buildMiniMaxClient() {
        AppSettings.ModelConfig modelConfig = settingsService.getSettings().getModelConfig();
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        String baseUrl = "https://api.minimaxi.com/v1";

        if (apiKey == null || apiKey.isBlank()) {
            log.error("[EmbeddingConfig] API Key 未配置，请在设置中配置 MiniMax API Key");
        }

        log.info("[EmbeddingConfig] MiniMax 模式: baseUrl={}", baseUrl);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private RestClient buildOllamaClient() {
        log.info("[EmbeddingConfig] Ollama 本地模式: baseUrl={}, model={}", bgeBaseUrl, bgeModel);
        log.info("[EmbeddingConfig] 如需切换到 MiniMax API，设置 spring.ai.embedding.provider=minimax");

        return RestClient.builder()
                .baseUrl(bgeBaseUrl.endsWith("/") ? bgeBaseUrl : bgeBaseUrl + "/")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public String getBgeModel() {
        return bgeModel;
    }
}
