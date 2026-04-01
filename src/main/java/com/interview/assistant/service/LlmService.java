package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.model.AppSettings.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.chat.ChatCompletion;
import org.springframework.ai.openai.chat.ChatCompletionRequest;
import org.springframework.ai.openai.chat.ChatCompletionRequest.ChatCompletionRequestBuilder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LlmService {

    private final ObjectMapper objectMapper;

    public LlmService() {
        this.objectMapper = new ObjectMapper();
    }

    public record LlmResult(String content, String error) {}

    public LlmResult callLlm(List<Map<String, String>> messages, ModelConfig config, Double temperature, Integer maxTokens) {
        String type = config.getType() != null ? config.getType() : "openai";
        String apiKey = config.getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            return new LlmResult(null, "API Key未配置");
        }

        try {
            return switch (type) {
                case "openai" -> callOpenAi(messages, config, temperature, maxTokens);
                case "claude" -> callClaude(messages, config, temperature, maxTokens);
                case "minimax" -> callMinimax(messages, config, temperature, maxTokens);
                case "custom" -> callCustom(messages, config, temperature, maxTokens);
                default -> callOpenAi(messages, config, temperature, maxTokens);
            };
        } catch (Exception e) {
            log.error("LLM调用失败: type={}", type, e);
            return new LlmResult(null, e.getMessage());
        }
    }

    private LlmResult callOpenAi(List<Map<String, String>> messages, ModelConfig config, Double temperature, Integer maxTokens) {
        String model = config.getModel() != null ? config.getModel() : "gpt-4";
        Double temp = temperature != null ? temperature : 0.7;
        Integer tokens = maxTokens != null ? maxTokens : 2000;
        String apiKey = config.getApiKey();
        String baseUrl = config.getBaseUrl();

        try {
            OpenAiApi api;
            if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.equals("https://api.openai.com/v1")) {
                api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
            } else {
                api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .build();
            }

            OpenAiChatModel chatModel = new OpenAiChatModel(api);

            List<Message> springMessages = new ArrayList<>();
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                switch (role) {
                    case "system" -> springMessages.add(new SystemMessage(content));
                    case "user" -> springMessages.add(new UserMessage(content));
                    default -> springMessages.add(new UserMessage(content));
                }
            }

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(springMessages)
                .model(model)
                .temperature(temp)
                .maxTokens(tokens)
                .build();

            ChatCompletion result = chatModel.call(request);
            String content = result.getResult().getOutput().getContent();
            return new LlmResult(content, null);

        } catch (Exception e) {
            log.error("OpenAI调用失败", e);
            return new LlmResult(null, e.getMessage());
        }
    }

    private LlmResult callClaude(List<Map<String, String>> messages, ModelConfig config, Double temperature, Integer maxTokens) {
        // Claude via OpenAI-compatible API (Anthropic provides OpenAI-compatible endpoint)
        ModelConfig customConfig = ModelConfig.builder()
            .type("custom")
            .apiKey(config.getApiKey())
            .baseUrl("https://api.anthropic.com/v1")
            .model(config.getModel() != null ? config.getModel() : "claude-3-5-sonnet-20241022")
            .build();
        return callCustom(messages, customConfig, temperature, maxTokens);
    }

    private LlmResult callMinimax(List<Map<String, String>> messages, ModelConfig config, Double temperature, Integer maxTokens) {
        // MiniMax uses OpenAI-compatible API
        ModelConfig customConfig = ModelConfig.builder()
            .type("custom")
            .apiKey(config.getApiKey())
            .baseUrl(config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.minimax.chat/v1")
            .model(config.getModel() != null ? config.getModel() : "MiniMax-M2.7")
            .build();
        return callCustom(messages, customConfig, temperature, maxTokens);
    }

    private LlmResult callCustom(List<Map<String, String>> messages, ModelConfig config, Double temperature, Integer maxTokens) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return callOpenAi(messages, config, temperature, maxTokens);
        }

        String apiKey = config.getApiKey();
        String model = config.getModel() != null ? config.getModel() : "gpt-4";

        try {
            OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

            OpenAiChatModel chatModel = new OpenAiChatModel(api);

            List<Message> springMessages = new ArrayList<>();
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                switch (role) {
                    case "system" -> springMessages.add(new SystemMessage(content));
                    case "user" -> springMessages.add(new UserMessage(content));
                    default -> springMessages.add(new UserMessage(content));
                }
            }

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(springMessages)
                .model(model)
                .temperature(temperature != null ? temperature : 0.7)
                .maxTokens(maxTokens != null ? maxTokens : 2000)
                .build();

            ChatCompletion result = chatModel.call(request);
            return new LlmResult(result.getResult().getOutput().getContent(), null);

        } catch (Exception e) {
            log.error("Custom LLM调用失败: baseUrl={}", baseUrl, e);
            return new LlmResult(null, e.getMessage());
        }
    }
}
