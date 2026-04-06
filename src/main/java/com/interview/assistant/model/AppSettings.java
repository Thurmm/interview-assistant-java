package com.interview.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("interviewer_name")
    private String interviewerName;
    
    private String company;
    
    private String position;
    
    private String experience;
    
    @JsonProperty("interview_type")
    private String interviewType;
    
    @JsonProperty("voice_provider")
    private String voiceProvider;
    
    @JsonProperty("baidu_voice")
    private BaiduVoice baiduVoice;
    
    @JsonProperty("xfyun_voice")
    private XfyunVoice xfyunVoice;
    
    @JsonProperty("model_config")
    private ModelConfig modelConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BaiduVoice {
        @JsonProperty("app_id")
        private String appId;
        
        @JsonProperty("api_key")
        private String apiKey;
        
        @JsonProperty("secret_key")
        private String secretKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class XfyunVoice {
        @JsonProperty("app_id")
        private String appId;
        
        @JsonProperty("api_key")
        private String apiKey;
        
        @JsonProperty("api_secret")
        private String apiSecret;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelConfig {
        private String type;
        @JsonProperty("api_key")
        private String apiKey;
        @JsonProperty("base_url")
        private String baseUrl;
        private String model;
    }
}
