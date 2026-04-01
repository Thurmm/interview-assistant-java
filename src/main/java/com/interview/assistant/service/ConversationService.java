package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interview.assistant.dto.AnswerResponse;
import com.interview.assistant.dto.StartConvoResponse;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.model.AppSettings.ModelConfig;
import com.interview.assistant.model.Conversation;
import com.interview.assistant.model.Message;
import com.interview.assistant.util.JsonFileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String CONVOS_FILE = "conversations.json";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JsonFileUtil jsonFileUtil;
    private final LlmService llmService;
    private final SettingsService settingsService;

    public List<Conversation> getAllConversations() {
        return jsonFileUtil.readJson(CONVOS_FILE, List.class, List.of());
    }

    public Optional<Conversation> getConversation(String id) {
        return getAllConversations().stream()
            .filter(c -> id.equals(c.getId()))
            .findFirst();
    }

    public StartConvoResponse startConversation() {
        AppSettings settings = settingsService.getSettings();
        ModelConfig modelConfig = settings.getModelConfig();

        if (modelConfig == null || modelConfig.getApiKey() == null || modelConfig.getApiKey().isBlank()) {
            throw new IllegalStateException("请先在设置中配置大模型API");
        }

        String convoId = UUID.randomUUID().toString().substring(0, 8);
        String now = LocalDateTime.now().format(DTF);

        String interviewerName = settings.getInterviewerName() != null ? settings.getInterviewerName() : "面试官";
        String company = settings.getCompany() != null ? settings.getCompany() : "";
        String position = settings.getPosition() != null ? settings.getPosition() : "";

        String welcome = String.format("你好，欢迎来到%s！我是%s，今天我们将进行%s岗位的面试。准备好了吗？我们开始吧。",
            company, interviewerName, position);

        Map<String, Object> settingsMap = new HashMap<>();
        settingsMap.put("company", company);
        settingsMap.put("position", position);
        settingsMap.put("experience", settings.getExperience());
        settingsMap.put("interviewType", settings.getInterviewType());
        settingsMap.put("interviewerName", interviewerName);
        settingsMap.put("modelConfig", modelConfig);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
            .role("interviewer")
            .content(welcome)
            .timestamp(now)
            .build());

        Conversation convo = Conversation.builder()
            .id(convoId)
            .createdAt(now)
            .updatedAt(now)
            .settings(settingsMap)
            .messages(messages)
            .currentQuestionIndex(0)
            .status("in_progress")
            .build();

        // 生成第一个问题
        String firstQuestion = generateInterviewQuestion(convo, modelConfig);
        String now2 = LocalDateTime.now().format(DTF);
        messages.add(Message.builder()
            .role("interviewer")
            .content(firstQuestion)
            .timestamp(now2)
            .isQuestion(true)
            .build());
        convo.setCurrentQuestionIndex(1);
        convo.setUpdatedAt(now2);

        // 保存
        List<Conversation> convos = getAllConversations();
        convos.add(0, convo);
        saveConversations(convos);

        return StartConvoResponse.builder()
            .convoId(convoId)
            .welcome(welcome)
            .firstQuestion(firstQuestion)
            .convo(convo)
            .build();
    }

    public AnswerResponse answer(String convoId, String userAnswer) {
        Conversation convo = getConversation(convoId)
            .orElseThrow(() -> new IllegalArgumentException("对话不存在"));

        ModelConfig modelConfig = extractModelConfig(convo);
        String now = LocalDateTime.now().format(DTF);

        // 获取当前问题
        String currentQuestion = convo.getMessages().stream()
            .filter(m -> Boolean.TRUE.equals(m.getIsQuestion()))
            .reduce((first, second) -> second)
            .map(Message::getContent)
            .orElse("");

        // 评估回答
        AnswerResponse.EvaluationResult evaluation = evaluateAnswer(currentQuestion, userAnswer, convo, modelConfig);

        // 添加用户回答
        convo.getMessages().add(Message.builder()
            .role("user")
            .content(userAnswer)
            .timestamp(now)
            .score(evaluation.getScore())
            .feedback(evaluation.getFeedback())
            .modelAnswer(evaluation.getModelAnswer())
            .build());

        boolean shouldEnd = shouldEndInterview(convo, modelConfig);

        if (shouldEnd) {
            String closing = generateClosingMessage(convo, modelConfig);
            convo.getMessages().add(Message.builder()
                .role("interviewer")
                .content(closing)
                .timestamp(LocalDateTime.now().format(DTF))
                .build());
            convo.setStatus("completed");
        } else {
            String nextQuestion = generateInterviewQuestion(convo, modelConfig);
            convo.getMessages().add(Message.builder()
                .role("interviewer")
                .content(nextQuestion)
                .timestamp(LocalDateTime.now().format(DTF))
                .isQuestion(true)
                .build());
            convo.setCurrentQuestionIndex(convo.getCurrentQuestionIndex() + 1);
        }

        convo.setUpdatedAt(LocalDateTime.now().format(DTF));

        // 找到下一个问题
        String nextQ = null;
        if (!"completed".equals(convo.getStatus())) {
            List<Message> msgs = convo.getMessages();
            for (int i = msgs.size() - 2; i >= 0; i--) {
                if (Boolean.TRUE.equals(msgs.get(i).getIsQuestion())) {
                    nextQ = msgs.get(i).getContent();
                    break;
                }
            }
        }

        // 保存
        List<Conversation> convos = getAllConversations();
        for (int i = 0; i < convos.size(); i++) {
            if (convos.get(i).getId().equals(convoId)) {
                convos.set(i, convo);
                break;
            }
        }
        saveConversations(convos);

        return AnswerResponse.builder()
            .evaluation(evaluation)
            .nextQuestion(nextQ)
            .isFinished("completed".equals(convo.getStatus()))
            .messages(convo.getMessages())
            .build();
    }

    public void stopConversation(String convoId, List<Message> messages, String status) {
        List<Conversation> convos = getAllConversations();
        for (int i = 0; i < convos.size(); i++) {
            if (convos.get(i).getId().equals(convoId)) {
                convos.get(i).setMessages(messages);
                convos.get(i).setStatus(status != null ? status : "stopped");
                convos.get(i).setUpdatedAt(LocalDateTime.now().format(DTF));
                break;
            }
        }
        saveConversations(convos);
    }

    public void deleteConversation(String convoId) {
        List<Conversation> convos = getAllConversations();
        convos = convos.stream()
            .filter(c -> !c.getId().equals(convoId))
            .collect(Collectors.toList());
        saveConversations(convos);
    }

    private String generateInterviewQuestion(Conversation convo, ModelConfig modelConfig) {
        String position = getSetting(convo, "position", "软件工程师");
        String experience = getSetting(convo, "experience", "");
        int questionIndex = convo.getCurrentQuestionIndex();

        String prompt = String.format("""
            你是一位专业的面试官，正在面试一位应聘%s岗位（要求%s经验）的候选人。
            这是第%d道问题。

            请根据面试进度问一个合适的面试问题。
            要求：
            1. 如果是开场，问一些基础但重要的问题（如自我介绍、项目经验）
            2. 随着面试深入，问题应该逐渐深入技术细节
            3. 问题要具体、有深度，能真正考察候选人的能力
            4. 只输出问题本身，不要加前缀说明
            """, position, experience, questionIndex + 1);

        LlmService.LlmResult result = llmService.callLlm(
            List.of(
                Map.of("role", "system", "content", "你是一位专业、友善的面试官。"),
                Map.of("role", "user", "content", prompt)
            ),
            modelConfig, 0.7, 500
        );

        if (result.error() != null) {
            return "抱歉，生成问题时遇到了点问题，请稍后重试。";
        }

        return cleanJsonWrapper(result.content());
    }

    private AnswerResponse.EvaluationResult evaluateAnswer(String question, String answer, Conversation convo, ModelConfig modelConfig) {
        if (answer == null || answer.isBlank()) {
            return AnswerResponse.EvaluationResult.builder()
                .score(0)
                .feedback("未检测到有效回答")
                .modelAnswer("请提供一个有效的回答")
                .build();
        }

        String position = getSetting(convo, "position", "软件工程师");
        String experience = getSetting(convo, "experience", "");

        String prompt = String.format("""
            你是一位专业的面试官，正在评估一位应聘%s岗位（要求%s经验）的候选人的回答。

            面试问题：%s

            候选人的回答：%s

            请从以下几个维度评估回答：
            1. 回答的完整性和相关性
            2. 展现的专业能力和经验
            3. 表达的逻辑性和清晰度
            4. 回答的深度和洞察力

            请用JSON格式回复，包含以下字段：
            - score: 0-10的整数评分
            - feedback: 简短的评价反馈（1-2句话）
            - model_answer: 一个更好的回答范例（2-3句话）

            只返回JSON，不要其他内容。
            """, position, experience, question, answer);

        LlmService.LlmResult result = llmService.callLlm(
            List.of(
                Map.of("role", "system", "content", "你是一位专业的面试官评估专家。评估要客观公正。"),
                Map.of("role", "user", "content", prompt)
            ),
            modelConfig, 0.3, 1000
        );

        if (result.error() != null) {
            return AnswerResponse.EvaluationResult.builder()
                .score(5)
                .feedback("回答已记录")
                .modelAnswer("建议从实际项目经验出发，结合具体案例来回答会更好。")
                .build();
        }

        try {
            String jsonStr = cleanJsonWrapper(result.content());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(jsonStr);

            int score = node.has("score") ? node.get("score").asInt() : 5;
            score = Math.max(0, Math.min(10, score));

            return AnswerResponse.EvaluationResult.builder()
                .score(score)
                .feedback(node.has("feedback") ? node.get("feedback").asText() : "回答已记录")
                .modelAnswer(node.has("model_answer") ? node.get("model_answer").asText() : "建议从实际项目经验出发。")
                .build();
        } catch (Exception e) {
            log.error("解析评估结果失败: {}", result.content(), e);
            return AnswerResponse.EvaluationResult.builder()
                .score(5)
                .feedback("回答已记录")
                .modelAnswer("建议从实际项目经验出发，结合具体案例来回答会更好。")
                .build();
        }
    }

    private boolean shouldEndInterview(Conversation convo, ModelConfig modelConfig) {
        int questionCount = convo.getCurrentQuestionIndex() != null ? convo.getCurrentQuestionIndex() : 0;

        if (questionCount >= 10) {
            return true;
        }

        if (questionCount < 3) {
            return false;
        }

        String position = getSetting(convo, "position", "软件工程师");
        StringBuilder history = new StringBuilder();
        int qCount = 0;
        for (Message msg : convo.getMessages()) {
            if (Boolean.TRUE.equals(msg.getIsQuestion())) {
                qCount++;
                history.append(String.format("Q%d: %s%n", qCount, msg.getContent()));
            } else if ("user".equals(msg.getRole())) {
                history.append(String.format("回答: %s%n", msg.getContent().substring(0, Math.min(100, msg.getContent().length()))));
            }
        }

        String prompt = String.format("""
            这是一个%s岗位的面试，目前问了%d个问题。

            面试摘要：
            %s

            基于这个对话，你觉得面试是否已经充分评估了候选人？
            请回答"结束"或"继续"，不要加其他内容。
            """, position, questionCount, history);

        LlmService.LlmResult result = llmService.callLlm(
            List.of(
                Map.of("role", "system", "content", "你是一位专业的面试官。"),
                Map.of("role", "user", "content", prompt)
            ),
            modelConfig, 0.1, 50
        );

        String content = result.content() != null ? result.content().trim().toLowerCase() : "";
        return content.contains("结束");
    }

    private String generateClosingMessage(Conversation convo, ModelConfig modelConfig) {
        String position = getSetting(convo, "position", "");
        String company = getSetting(convo, "company", "");

        String prompt = String.format("""
                作为%s的面试官，请为一位应聘%s岗位的面试生成一段简短的结束语。

                要求：
                1. 感谢候选人的参与
                2. 告知面试结束
                3. 说明后续流程
                4. 保持专业和友善

                直接输出结束语，不要加前缀。
                """, company, position);

        LlmService.LlmResult result = llmService.callLlm(
            List.of(
                Map.of("role", "system", "content", "你是一位专业、友善的面试官。"),
                Map.of("role", "user", "content", prompt)
            ),
            modelConfig, 0.7, 300
        );

        return result.content() != null ? result.content().trim()
            : "非常感谢你的参与，今天的面试到此结束。我们会在一周内通知你结果。祝你好运！";
    }

    private ModelConfig extractModelConfig(Conversation convo) {
        Object modelConfigObj = convo.getSettings().get("modelConfig");
        if (modelConfigObj == null) {
            return ModelConfig.builder().type("openai").build();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(modelConfigObj);
            return mapper.readValue(json, ModelConfig.class);
        } catch (JsonProcessingException e) {
            log.error("解析modelConfig失败", e);
            return ModelConfig.builder().type("openai").build();
        }
    }

    private String getSetting(Conversation convo, String key, String defaultValue) {
        Object val = convo.getSettings().get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private String cleanJsonWrapper(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        // Remove markdown code blocks
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private void saveConversations(List<Conversation> convos) {
        // Convert to a generic list for JSON serialization
        List<Map<String, Object>> data = convos.stream()
            .map(this::toMap)
            .collect(Collectors.toList());
        jsonFileUtil.writeJson(CONVOS_FILE, data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Conversation c) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.getId());
        map.put("createdAt", c.getCreatedAt());
        map.put("updatedAt", c.getUpdatedAt());
        map.put("settings", c.getSettings());
        map.put("messages", c.getMessages());
        map.put("currentQuestionIndex", c.getCurrentQuestionIndex());
        map.put("status", c.getStatus());
        return map;
    }
}
