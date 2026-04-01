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
                .build())
            .build();

        return jsonFileUtil.readJson(SETTINGS_FILE, AppSettings.class, defaultSettings);
    }

    public void saveSettings(AppSettings settings) {
        jsonFileUtil.writeJson(SETTINGS_FILE, settings);
    }
}
