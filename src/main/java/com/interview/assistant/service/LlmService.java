package com.interview.assistant.service;

import com.interview.assistant.model.AppSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final ChatModel chatModel;

    public String callLlm(
            List<Map<String, String>> messages,
            AppSettings.ModelConfig modelConfig
    ) {
        try {
            List<Message> aiMessages = new ArrayList<>();
            
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                
                if ("system".equals(role)) {
                    // System messages are handled as user messages with system prefix
                    aiMessages.add(new UserMessage("[SYSTEM] " + content));
                } else if ("user".equals(role)) {
                    aiMessages.add(new UserMessage(content));
                }
            }

            String content = null;
            try {
                Prompt prompt = new Prompt(aiMessages);
                ChatResponse response = chatModel.call(prompt);
                content = response.getResult().getOutput().getContent();
            } catch (Exception e) {
                log.error("[LlmService] 解析响应失败: {}", e.getMessage());
            }

            log.info("[LlmService] API响应前80字: {}", content != null ? content.substring(0, Math.min(80, content.length())) : "null");
            return content;

        } catch (Exception e) {
            log.error("[LlmService] 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 测试模型连接
     */
    public boolean testConnection(AppSettings.ModelConfig modelConfig) {
        try {
            // 测试连接，发送一个简单的请求
            List<Map<String, String>> testMessages = List.of(
                    Map.of("role", "system", "content", "你是一个测试助手"),
                    Map.of("role", "user", "content", "测试连接，请返回'连接成功'"));
            
            String result = callLlm(testMessages, modelConfig);
            return result != null && result.contains("连接成功");
        } catch (Exception e) {
            log.error("[LlmService] 连接测试失败: {}", e.getMessage());
            return false;
        }
    }
}
