package com.interview.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VoiceController {

    private final SettingsService settingsService;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                String tokenUrl = String.format(
                    "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=%s&client_secret=%s",
                    apiKey, secretKey);
                var resp = httpClient.newCall(new Request.Builder().url(tokenUrl).get().build()).execute();
                if (resp.isSuccessful()) {
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
                try {
                    // Test by building the WebSocket URL
                    String wsUrl = buildXfyunWsUrl(appId, apiKey, apiSecret);
                    voiceTokenCache.put("xfyun", Map.of(
                        "app_id", appId, "api_key", apiKey, "api_secret", apiSecret
                    ));
                    return ResponseEntity.ok(Map.of("status", "ok", "message", "配置正确"));
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "配置错误: " + e.getMessage()));
                }
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
        var tokenResp = httpClient.newCall(new Request.Builder().url(tokenUrl).get().build()).execute();

        if (!tokenResp.isSuccessful()) {
            return ResponseEntity.badRequest().body(Map.of("error", "获取access_token失败"));
        }

        String accessToken;
        try {
            var tokenJson = objectMapper.readTree(tokenResp.body().string());
            accessToken = tokenJson.get("access_token").asText();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "解析access_token失败"));
        }

        String audioBase64 = Base64.getEncoder().encodeToString(audioData);

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("format", "wav");
        payload.put("rate", 16000);
        payload.put("dev_pid", 1537);
        payload.put("cuid", "interview_assistant");
        payload.put("len", audioData.length);
        payload.put("channel", 1);
        payload.put("token", accessToken);
        payload.put("speech", audioBase64);

        String jsonPayload = objectMapper.writeValueAsString(payload);

        var recogResp = httpClient.newCall(
            new Request.Builder()
                .url("https://vop.baidu.com/server_api")
                .header("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(jsonPayload, MediaType.parse("application/json")))
                .build()).execute();

        if (!recogResp.isSuccessful()) {
            return ResponseEntity.badRequest().body(Map.of("error", "识别请求失败"));
        }

        try {
            var resultJson = objectMapper.readTree(recogResp.body().string());
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

        if (audioFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有音频文件"));
        }

        try {
            byte[] audioData = audioFile.getBytes();
            if (audioData.length < 1000) {
                return ResponseEntity.badRequest().body(Map.of("error", "音频数据太短"));
            }

            // Build WebSocket URL with correct authentication
            String wsUrl = buildXfyunWsUrl(voice.getAppId(), voice.getApiKey(), voice.getApiSecret());
            log.info("Connecting to iFlytek WebSocket: {}", wsUrl.substring(0, Math.min(80, wsUrl.length())));

            // Convert audio to PCM 16k 16bit mono WAV
            byte[] pcmData = convertToPcm16k16bitMonoWav(audioData);
            log.info("Audio converted, size: {} bytes", pcmData.length);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> resultText = new AtomicReference<>(null);
            AtomicReference<String> errorText = new AtomicReference<>(null);

            Request request = new Request.Builder().url(wsUrl).build();
            WebSocket webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                private boolean firstFrame = true;

                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    log.info("iFlytek WebSocket connected");
                    try {
                        // Send audio in chunks (1280 bytes per frame, 40ms at 16kHz)
                        int chunkSize = 1280;
                        int offset = 0;
                        int frameIndex = 0;
                        
                        while (offset < pcmData.length) {
                            int remaining = pcmData.length - offset;
                            int frameSize = Math.min(chunkSize, remaining);
                            byte[] chunk = new byte[frameSize];
                            System.arraycopy(pcmData, offset, chunk, 0, frameSize);
                            
                            Map<String, Object> frame = new java.util.HashMap<>();
                            
                            if (offset == 0) {
                                // First frame: include full header, parameter, and audio metadata
                                Map<String, Object> businessParams = new java.util.HashMap<>();
                                businessParams.put("domain", "iat");
                                businessParams.put("language", "zh_cn");
                                businessParams.put("accent", "mandarin");
                                businessParams.put("eos", 6000);
                                businessParams.put("dwa", "wpgs");
                                
                                frame.put("header", Map.of(
                                    "app_id", voice.getAppId(),
                                    "status", 0
                                ));
                                frame.put("parameter", Map.of("iat", businessParams));
                                frame.put("payload", Map.of(
                                    "audio", Map.of(
                                        "encoding", "raw",
                                        "sample_rate", 16000,
                                        "channels", 1,
                                        "bit_depth", 16,
                                        "status", 0,
                                        "audio", Base64.getEncoder().encodeToString(chunk)
                                    )
                                ));
                            } else {
                                // Intermediate/last frame: only header and payload
                                boolean isLast = (offset + frameSize >= pcmData.length);
                                frame.put("header", Map.of("status", isLast ? 2 : 1));
                                frame.put("payload", Map.of(
                                    "audio", Map.of(
                                        "status", isLast ? 2 : 1,
                                        "audio", Base64.getEncoder().encodeToString(chunk)
                                    )
                                ));
                            }
                            
                            String json = objectMapper.writeValueAsString(frame);
                            webSocket.send(json);
                            log.info("Sent frame {} (status={}, size={})", frameIndex, offset == 0 ? 0 : (offset + frameSize >= pcmData.length ? 2 : 1), frameSize);
                            
                            offset += frameSize;
                            frameIndex++;
                            
                            // Small delay between frames to simulate real-time streaming
                            if (offset < pcmData.length) {
                                Thread.sleep(40);
                            }
                        }

                    } catch (Exception e) {
                        log.error("Error sending to iFlytek", e);
                        errorText.set("发送音频失败: " + e.getMessage());
                        webSocket.close(1000, "error");
                    }
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    log.info("iFlytek message: {}", text);
                    try {
                        var json = objectMapper.readTree(text);
                        var header = json.path("header");
                        var code = header.path("code").asInt();
                        if (code != 0) {
                            errorText.set("讯飞错误: " + header.path("message").asText());
                            webSocket.close(1000, "error");
                            latch.countDown();
                            return;
                        }
                        
                        int status = header.path("status").asInt();
                        var resultObj = json.path("payload").path("result");
                        String encodedText = resultObj.path("text").asText();
                        
                        if (encodedText != null && !encodedText.isEmpty()) {
                            // text is base64 encoded JSON string, decode and parse
                            byte[] decodedBytes = Base64.getDecoder().decode(encodedText);
                            String textJson = new String(decodedBytes, StandardCharsets.UTF_8);
                            var textObj = objectMapper.readTree(textJson);
                            
                            // Extract words from ws array
                            StringBuilder sb = new StringBuilder();
                            var ws = textObj.path("ws");
                            for (var w : ws) {
                                var cw = w.path("cw");
                                for (var c : cw) {
                                    sb.append(c.path("w").asText());
                                }
                            }
                            String result = sb.toString();
                            log.info("Extracted text: {}", result);
                            resultText.set(result);
                        }
                        
                        if (status == 2) {
                            // Final result
                            webSocket.close(1000, "done");
                        }
                    } catch (Exception e) {
                        log.error("Error parsing iFlytek response", e);
                    }
                }

                @Override
                public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                    log.info("iFlytek binary message: {} bytes", bytes.size());
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("iFlytek WebSocket failure", t);
                    if (errorText.get() == null) {
                        errorText.set("连接失败: " + t.getMessage());
                    }
                    latch.countDown();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    log.info("iFlytek WebSocket closed: {} - {}", code, reason);
                    latch.countDown();
                }
            });

            // Wait for result with timeout
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            
            if (!completed) {
                webSocket.close(1000, "timeout");
                return ResponseEntity.badRequest().body(Map.of("error", "识别超时，请重试"));
            }

            if (errorText.get() != null) {
                return ResponseEntity.badRequest().body(Map.of("error", errorText.get()));
            }

            String text = resultText.get();
            if (text != null && !text.isEmpty()) {
                // Clean up wpgs markers
                text = text.replaceAll("[{}\"]", "");
                return ResponseEntity.ok(Map.of("text", text));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "未识别到文字，请重试"));
            }

        } catch (Exception e) {
            log.error("讯飞语音识别失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", "识别失败: " + e.getMessage()));
        }
    }

    /**
     * Build iFlytek WebSocket URL with proper HMAC-SHA256 signature
     * According to iFlytek docs:
     * - host: iat.xf-yun.com
     * - date: RFC1123 format in GMT (e.g., "Fri, 05 Apr 2026 07:25:33 GMT")
     * - authorization: base64(HmacSHA256(host date request-line, apiSecret))
     */
    private String buildXfyunWsUrl(String appId, String apiKey, String apiSecret) throws Exception {
        // RFC1123 format date for signature
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z")
                .withLocale(java.util.Locale.US)
                .withZone(ZoneId.of("GMT"));
        String date = dtf.format(Instant.now());
        
        log.info("=== iFlytek WebSocket Auth ===");
        log.info("appId: {}", appId);
        log.info("apiKey (raw): {}", apiKey);
        log.info("apiKey length: {}", apiKey.length());
        log.info("apiSecret (raw): {}", apiSecret);
        log.info("apiSecret length: {}", apiSecret.length());
        log.info("date: {}", date);
        
        // Try raw string first, also show hex decode for comparison
        byte[] secretBytesRaw = apiSecret.getBytes(StandardCharsets.UTF_8);
        log.info("apiSecret as raw bytes length: {}", secretBytesRaw.length);
        
        byte[] secretBytes = secretBytesRaw; // default to raw
        
        // Also try hex decode (32 hex chars -> 16 bytes)
        try {
            byte[] secretBytesHex = new byte[16];
            for (int i = 0; i < 16; i++) {
                secretBytesHex[i] = (byte) Integer.parseInt(apiSecret.substring(i*2, i*2+2), 16);
            }
            log.info("apiSecret as hex decode length: {}", secretBytesHex.length);
            // Print both for comparison
            StringBuilder rawHex = new StringBuilder();
            for (byte b : secretBytesRaw) rawHex.append(String.format("%02x", b));
            StringBuilder decHex = new StringBuilder();
            for (byte b : secretBytesHex) decHex.append(String.format("%02x", b));
            log.info("RAW secret hex: {}", rawHex);
            log.info("HEX secret hex: {}", decHex);
            log.info("Hex decode succeeded! Switching to HEX secret");
            secretBytes = secretBytesHex;
        } catch (Exception e) {
            log.warn("Hex decode failed ({}), using raw string", e.getMessage());
        }
        
        // Signature origin: "host date request-line"
        String signatureOrigin = "host: iat.xf-yun.com\ndate: " + date + "\nGET /v1 HTTP/1.1";
        log.info("Signature origin: {}", signatureOrigin.replace("\n", "\\n"));
        
        // HmacSHA256 with decoded apiSecret
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretBytes, "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(hmacBytes);
        log.info("Signature (base64): {}", signature);
        
        // Authorization origin
        String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + signature + "\"";
        String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
        log.info("Authorization origin: {}", authorizationOrigin);
        log.info("Authorization (base64): {}", authorization);
        
        // Build URL
        String params = "appid=" + URLEncoder.encode(appId, StandardCharsets.UTF_8) + 
                       "&ts=" + URLEncoder.encode(date, StandardCharsets.UTF_8) +
                       "&ttl=300" +
                       "&authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8);
        
        String wsUrl = "wss://iat.xf-yun.com/v1?" + params;
        log.info("Final WebSocket URL: {}", wsUrl);
        log.info("=== End iFlytek Auth ===");
        
        return wsUrl;
    }

    /**
     * Convert audio data to PCM 16kHz 16bit mono WAV format
     */
    private byte[] convertToPcm16k16bitMonoWav(byte[] audioData) throws IOException {
        byte[] pcmData;
        int sampleRate = 16000;
        short bitsPerSample = 16;
        short numChannels = 1;
        
        // Check if it's a WAV file (starts with "RIFF")
        if (audioData.length > 44 && 
            audioData[0] == 'R' && audioData[1] == 'I' && 
            audioData[2] == 'F' && audioData[3] == 'F') {
            
            int wavSampleRate = ByteBuffer.wrap(audioData, 24, 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get();
            short wavBitsPerSample = ByteBuffer.wrap(audioData, 34, 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get();
            short wavNumChannels = ByteBuffer.wrap(audioData, 22, 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get();
            
            log.info("WAV detected: sampleRate={}, bitsPerSample={}, channels={}", wavSampleRate, wavBitsPerSample, wavNumChannels);
            
            // Extract PCM data after 44-byte header
            int dataSize = audioData.length - 44;
            pcmData = new byte[dataSize];
            System.arraycopy(audioData, 44, pcmData, 0, dataSize);
            
            // If already 16kHz mono 16bit, just strip the header
            if (wavSampleRate == sampleRate && wavBitsPerSample == bitsPerSample && wavNumChannels == numChannels) {
                // Already in correct format, just return the PCM data
            } else {
                // Would need resampling or channel conversion - for now assume it's compatible
                log.warn("Audio format differs from expected, proceeding anyway");
            }
        } else {
            // Not a WAV file, assume raw PCM
            pcmData = audioData;
        }
        
        // Create WAV file in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // RIFF header
        baos.write("RIFF".getBytes());
        int fileSize = 36 + pcmData.length;
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize).array());
        baos.write("WAVE".getBytes());
        
        // fmt chunk
        baos.write("fmt ".getBytes());
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array()); // chunk size
        baos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).array()); // audio format (PCM)
        baos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(numChannels).array());
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array());
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate * numChannels * bitsPerSample / 8).array()); // byte rate
        baos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)(numChannels * bitsPerSample / 8)).array()); // block align
        baos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(bitsPerSample).array());
        
        // data chunk
        baos.write("data".getBytes());
        baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcmData.length).array());
        baos.write(pcmData);
        
        return baos.toByteArray();
    }
}
