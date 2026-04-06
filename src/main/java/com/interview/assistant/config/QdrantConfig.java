package com.interview.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通过命令行 curl 调用 LLM API
 *
 * 解决 WSL 环境下 Java HTTP client 的网络兼容性问题。
 */
@Slf4j
@Component
public class QdrantConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    public String callLlm(
            List<Map<String, String>> messages,
            String apiKey,
            String baseUrl,
            String model,
            Double temperature
    ) {
        String effectiveKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : defaultApiKey;
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.openai.com/v1";
        String effectiveModel = (model != null && !model.isBlank()) ? model : "gpt-4o-mini";

        String jsonBody = buildChatBody(messages, effectiveModel, temperature);

        String curlCmd = String.format(
                "curl -s --max-time 20 -X POST '%s/chat/completions' " +
                "-H 'Authorization: Bearer %s' " +
                "-H 'Content-Type: application/json' " +
                "-d '%s'",
                effectiveBaseUrl,
                effectiveKey,
                escapeShell(jsonBody)
        );

        System.out.println("[QdrantConfig] curl — url=" + effectiveBaseUrl + " model=" + effectiveModel);

        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", curlCmd);
            pb.environment().putAll(System.getenv());
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.out.println("[QdrantConfig] curl exit=" + exitCode + " output: " + output.substring(0, Math.min(150, output.length())));
                return null;
            }

            if (output == null || output.isBlank()) {
                System.out.println("[QdrantConfig] curl 返回空");
                return null;
            }

            String content = extractContent(output);
            System.out.println("[QdrantConfig] API响应前80字: " + (content != null ? content.substring(0, Math.min(80, content.length())) : "null"));
            return content;

        } catch (Exception e) {
            System.out.println("[QdrantConfig] curl 执行异常: " + e.getMessage());
            return null;
        }
    }

    private String buildChatBody(List<Map<String, String>> messages, String model, Double temperature) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(model).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(msg.get("role"))
              .append("\",\"content\":\"").append(escapeJson(msg.get("content"))).append("\"}");
        }
        sb.append("]");
        if (temperature != null) {
            sb.append(",\"temperature\":").append(temperature);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从 MiniMax / OpenAI API 响应中提取 content
     *
     * MiniMax 响应格式:
     * {"choices":[{"message":{"content":"<think>...</think> 实际回答"}}],"created":...}
     *
     * 需要正确处理转义字符和嵌套引号
     */
    private String extractContent(String text) {
        // 1. 找 JSON 开始
        int jsonStart = text.indexOf('{');
        if (jsonStart < 0) return text.substring(0, Math.min(200, text.length()));
        String json = text.substring(jsonStart);

        // 2. 找 "content" 字段位置
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx < 0) return json.substring(0, Math.min(200, json.length()));

        // 3. 从 content: 之后解析带转义的 JSON 字符串值
        int valueStart = -1, valueEnd = -1;
        boolean inValue = false;
        boolean escaped = false;

        for (int i = json.indexOf(':', contentIdx) + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inValue) {
                    valueStart = i + 1;
                    inValue = true;
                } else {
                    valueEnd = i;
                    break;
                }
            }
        }

        if (valueStart < 0 || valueEnd < 0 || valueEnd <= valueStart) {
            return json.substring(0, Math.min(200, json.length()));
        }

        String content = json.substring(valueStart, valueEnd);

        // 4. 去除 MiniMax 思考标签 <|im_start|>think ... <|STOP|>
        content = content
                .replaceAll("(<\\|im_start\\|>think.*?<\\|STOP\\|>)\\s*", " ")
                .replaceAll("<\\|im_end\\|>", "")
                .replaceAll("<\\|STOP\\|>", "")
                .replaceAll("<think>.*?</think>", "")
                .trim();

        return content.isEmpty() ? json.substring(0, Math.min(100, json.length())) : content;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeShell(String s) {
        return s.replace("'", "'\\''");
    }
}
