package com.interview.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {
    private String interviewerName;
    private String company;
    private String position;
    private String experience;
    private String interviewType;
    private String voiceProvider;
    private BaiduVoice baiduVoice;
    private XfyunVoice xfyunVoice;
    private ModelConfig modelConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BaiduVoice {
        private String appId;
        private String apiKey;
        private String secretKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class XfyunVoice {
        private String appId;
        private String apiKey;
        private String apiSecret;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelConfig {
        private String type;     // openai, claude, minimax, custom
        private String apiKey;
        private String baseUrl;
        private String model;
    }
}
