package com.interview.assistant.service;

import com.interview.assistant.model.AppSettings;
import com.interview.assistant.model.AppSettings.ModelConfig;
import com.interview.assistant.util.JsonFileUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String SETTINGS_FILE = "settings.json";

    private final JsonFileUtil jsonFileUtil;

    public AppSettings getSettings() {
        AppSettings defaultSettings = AppSettings.builder()
            .interviewerName("面试官小智")
            .company("某知名互联网公司")
            .position("Python开发工程师")
            .experience("3-5年")
            .interviewType("技术面试")
            .voiceProvider("baidu")
            .modelConfig(ModelConfig.builder()
                .type("openai")
                .apiKey("")
                .baseUrl("https://api.openai.com/v1")
                .model("gpt-4")
                .temperature(0.7)
                .build())
            .build();

        return jsonFileUtil.readJson(SETTINGS_FILE, AppSettings.class, defaultSettings);
    }

    public void saveSettings(AppSettings settings) {
        // 验证配置
        validateSettings(settings);
        jsonFileUtil.writeJson(SETTINGS_FILE, settings);
    }

    private void validateSettings(AppSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        
        // 验证模型配置
        if (settings.getModelConfig() != null) {
            AppSettings.ModelConfig modelConfig = settings.getModelConfig();
            if (modelConfig.getApiKey() != null && modelConfig.getApiKey().isBlank()) {
                throw new IllegalArgumentException("API密钥不能为空");
            }
            if (modelConfig.getBaseUrl() != null && modelConfig.getBaseUrl().isBlank()) {
                throw new IllegalArgumentException("Base URL不能为空");
            }
            if (modelConfig.getModel() != null && modelConfig.getModel().isBlank()) {
                throw new IllegalArgumentException("模型名称不能为空");
            }
            if (modelConfig.getTemperature() != null && (modelConfig.getTemperature() < 0 || modelConfig.getTemperature() > 1)) {
                throw new IllegalArgumentException("温度值必须在0-1之间");
            }
        }
        
        // 验证其他配置
        if (settings.getInterviewerName() != null && settings.getInterviewerName().isBlank()) {
            throw new IllegalArgumentException("面试官姓名不能为空");
        }
        if (settings.getPosition() != null && settings.getPosition().isBlank()) {
            throw new IllegalArgumentException("职位不能为空");
        }
        if (settings.getExperience() != null && settings.getExperience().isBlank()) {
            throw new IllegalArgumentException("经验要求不能为空");
        }
    }
}
