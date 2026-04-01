package com.interview.assistant.controller;

import com.interview.assistant.model.AppSettings;
import com.interview.assistant.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VoiceController {

    private final SettingsService settingsService;

    // Simple token cache (in production use Redis or similar)
    private final Map<String, Map<String, Object>> voiceTokenCache = new ConcurrentHashMap<>();

    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> testVoice(@RequestBody Map<String, String> body) {
        String provider = body.getOrDefault("provider", "baidu");
        try {
            if ("baidu".equals(provider)) {
                String appId = body.get("app_id");
                String apiKey = body.get("api_key");
                String secretKey = body.get("secret_key");
                if (appId == null || apiKey == null || secretKey == null) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "配置不完整"));
                }
                // Test by getting access token
                String tokenUrl = String.format(
                    "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=%s&client_secret=%s",
                    apiKey, secretKey);
                var resp = java.net.http.HttpClient.newHttpClient()
                    .sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(tokenUrl)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                    .join();
                if (resp.statusCode() == 200) {
                    return ResponseEntity.ok(Map.of("status", "ok", "message", "配置正确"));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "认证失败"));
                }
            } else if ("xfyun".equals(provider)) {
                String appId = body.get("app_id");
                String apiKey = body.get("api_key");
                String apiSecret = body.get("api_secret");
                if (appId == null || apiKey == null || apiSecret == null) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "配置不完整"));
                }
                voiceTokenCache.put("xfyun", new HashMap<>(Map.of(
                    "app_id", appId, "api_key", apiKey, "api_secret", apiSecret
                )));
                return ResponseEntity.ok(Map.of("status", "ok", "message", "配置正确"));
            }
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "未知服务商"));
        } catch (Exception e) {
            log.error("语音测试失败", e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/recognize")
    public ResponseEntity<Map<String, String>> recognize(@RequestParam("audio") MultipartFile audioFile) {
        AppSettings settings = settingsService.getSettings();
        String provider = settings.getVoiceProvider() != null ? settings.getVoiceProvider() : "baidu";

        try {
            if ("baidu".equals(provider)) {
                return recognizeBaidu(audioFile, settings);
            } else if ("xfyun".equals(provider)) {
                return recognizeXfyun(audioFile, settings);
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "未配置语音识别"));
            }
        } catch (Exception e) {
            log.error("语音识别失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", "识别失败: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, String>> recognizeBaidu(MultipartFile audioFile, AppSettings settings) throws IOException {
        AppSettings.BaiduVoice voice = settings.getBaiduVoice();
        if (voice == null || voice.getApiKey() == null || voice.getApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先在设置中配置百度语音识别"));
        }

        if (audioFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有音频文件"));
        }

        byte[] audioData = audioFile.getBytes();
        if (audioData.length < 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "音频数据太短"));
        }

        // Get access token
        String tokenUrl = String.format(
            "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=%s&client_secret=%s",
            voice.getApiKey(), voice.getSecretKey());
        var tokenResp = java.net.http.HttpClient.newHttpClient()
            .sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(tokenUrl)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .join();

        if (tokenResp.statusCode() != 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "获取access_token失败"));
        }

        String accessToken;
        try {
            var tokenJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tokenResp.body());
            accessToken = tokenJson.get("access_token").asText();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "解析access_token失败"));
        }

        String audioBase64 = java.util.Base64.getEncoder().encodeToString(audioData);

        Map<String, Object> payload = new HashMap<>();
        payload.put("format", "wav");
        payload.put("rate", 16000);
        payload.put("dev_pid", 1537);
        payload.put("cuid", "interview_assistant");
        payload.put("len", audioData.length);
        payload.put("channel", 1);
        payload.put("token", accessToken);
        payload.put("speech", audioBase64);

        String jsonPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);

        var recogResp = java.net.http.HttpClient.newHttpClient()
            .sendAsync(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://vop.baidu.com/server_api"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .join();

        if (recogResp.statusCode() != 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "识别请求失败"));
        }

        try {
            var resultJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(recogResp.body());
            if (resultJson.has("err_no") && resultJson.get("err_no").asInt() == 0) {
                String text = resultJson.get("result").get(0).asText();
                return ResponseEntity.ok(Map.of("text", text));
            } else {
                String errMsg = resultJson.has("err_msg") ? resultJson.get("err_msg").asText() : "未知错误";
                return ResponseEntity.badRequest().body(Map.of("error", "识别失败: " + errMsg));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "解析识别结果失败: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, String>> recognizeXfyun(MultipartFile audioFile, AppSettings settings) {
        AppSettings.XfyunVoice voice = settings.getXfyunVoice();
        if (voice == null || voice.getApiKey() == null || voice.getApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先在设置中配置讯飞语音识别"));
        }

        // For WebSocket-based Xunfei, we need to use a proper WebSocket client
        // In Java Spring, this would typically be done with Spring WebFlux WebSocket client
        // For simplicity, returning an error suggesting to use Baidu or implement async WebSocket
        return ResponseEntity.badRequest().body(Map.of("error", "讯飞WebSocket识别需要额外的异步处理，建议使用百度语音识别"));
    }
}
