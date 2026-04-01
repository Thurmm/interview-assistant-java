package com.interview.assistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Bean
    public OpenAiApi openAiApi() {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            // Return a dummy API - actual calls will fail gracefully
            // The app handles missing API key in service layer
            return null;
        }
        return OpenAiApi.builder()
            .apiKey(openAiApiKey)
            .build();
    }

    @Bean
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
        if (openAiApi == null) {
            return null;
        }
        return new OpenAiChatModel(openAiApi);
    }
}
