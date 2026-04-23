package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.agent.EvaluatorAgent;
import com.interview.assistant.agent.InterviewerAgent;
import com.interview.assistant.agent.InterviewerAgent.InterviewPhase;
import com.interview.assistant.dto.AnswerResponse;
import com.interview.assistant.dto.StartConvoResponse;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.model.AppSettings.ModelConfig;
import com.interview.assistant.model.CandidateProfile;
import com.interview.assistant.model.Conversation;
import com.interview.assistant.model.Message;
import com.interview.assistant.util.JsonFileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 对话服务（V2 重构：接入 Multi-Agent）
 *
 * 职责：
 * - 管理面试会话生命周期
 * - 协调 InterviewerAgent / EvaluatorAgent / VectorStoreService
 * - 持久化会话数据（JSON 文件）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String CONVOS_FILE = "conversations.json";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JsonFileUtil jsonFileUtil;
    private final SettingsService settingsService;
    private final InterviewerAgent interviewerAgent;
    private final EvaluatorAgent evaluatorAgent;
    private final VectorStoreService vectorStoreService;

    // ============ 内存缓存（解决高并发文件锁冲突）===========
    private final ConcurrentHashMap<String, Conversation> convoCache = new ConcurrentHashMap<>();
    private volatile long cacheVersion = 0;
    /** per-conversation 互斥锁，防止同一 ID 并发操作导致状态覆盖 */
    private final ConcurrentHashMap<String, ReentrantLock> convoLocks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(String convoId) {
        return convoLocks.computeIfAbsent(convoId, k -> new ReentrantLock());
    }

    // ============ 对话查询 ============

    public List<Conversation> getAllConversations() {
        return new ArrayList<>(convoCache.values());
    }

    public Optional<Conversation> getConversation(String id) {
        return getAllConversations().stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst();
    }

    // ============ 开始面试 ============

    /**
     * 开始新面试（无简历版本）
     */
    public StartConvoResponse startConversation() {
        return startConversationInternal(null);
    }

    /**
     * 开始新面试（带简历版本 — 个性化面试）
     *
     * @param candidateProfile 简历解析出的候选人画像（可选）
     */
    public StartConvoResponse startConversationWithResume(CandidateProfile candidateProfile) {
        return startConversationInternal(candidateProfile);
    }

    private StartConvoResponse startConversationInternal(CandidateProfile candidateProfile) {
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
        String experience = settings.getExperience() != null ? settings.getExperience() : "";

        String welcome;
        if (candidateProfile != null && candidateProfile.getName() != null && !candidateProfile.getName().isBlank()) {
            welcome = String.format("你好，%s！欢迎来到%s！我是%s，今天我们将进行%s岗位的面试。准备好了吗？我们开始吧。",
                    candidateProfile.getName(), company, interviewerName, position);
        } else {
            welcome = String.format("你好，欢迎来到%s！我是%s，今天我们将进行%s岗位的面试。准备好了吗？我们开始吧。",
                    company, interviewerName, position);
        }

        Map<String, Object> settingsMap = new HashMap<>();
        settingsMap.put("company", company);
        settingsMap.put("position", position);
        settingsMap.put("experience", experience);
        settingsMap.put("interviewType", settings.getInterviewType());
        settingsMap.put("interviewerName", interviewerName);
        settingsMap.put("modelConfig", modelConfig);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role("interviewer")
                .content(welcome)
                .timestamp(now)
                .build());

        // 构建候选人画像描述（供 Agent 使用）
        String profileSummary = buildProfileSummary(candidateProfile, position, experience);

        // 如果有简历技术栈，从向量库检索5道与技能相关的题目作为技术面出题指导
        String skillQuestionsContext = null;
        if (candidateProfile != null && candidateProfile.getTechStack() != null && !candidateProfile.getTechStack().isEmpty()) {
            try {
                skillQuestionsContext = vectorStoreService.retrieveSkillQuestions(candidateProfile.getTechStack(), 5);
            } catch (Exception e) {
                log.warn("检索技能相关题目失败: {}", e.getMessage());
            }
        }

        // 生成第一道问题（使用 InterviewerAgent）
        String firstQuestion = interviewerAgent.generateQuestion(
                profileSummary,
                position,
                experience,
                InterviewPhase.OPENING,
                1,
                messages,
                modelConfig,
                skillQuestionsContext
        );

        String now2 = LocalDateTime.now().format(DTF);
        messages.add(Message.builder()
                .role("interviewer")
                .content(firstQuestion)
                .timestamp(now2)
                .isQuestion(true)
                .build());

        Conversation convo = Conversation.builder()
                .id(convoId)
                .createdAt(now)
                .updatedAt(now2)
                .settings(settingsMap)
                .messages(messages)
                .currentQuestionIndex(1)
                .status("in_progress")
                .candidateProfile(candidateProfile)
                .interviewPhase(InterviewPhase.OPENING.name())
                .build();

        // 持久化
        List<Conversation> convos = new ArrayList<>(getAllConversations());
        convos.add(0, convo);
        saveConversations(convos);

        return StartConvoResponse.builder()
                .convoId(convoId)
                .welcome(welcome)
                .firstQuestion(firstQuestion)
                .convo(convo)
                .build();
    }

    // ============ 回答处理 ============

    /**
     * 处理用户回答
     *
     * 流程：
     * 1. 用 EvaluatorAgent 评分（含 RAG 检索参考答案）
     * 2. 判断是否结束（InterviewerAgent）
     * 3. 生成下一题（InterviewerAgent）
     */
    public AnswerResponse answer(String convoId, String userAnswer) {
        ReentrantLock lock = lockFor(convoId);
        lock.lock();
        try {
            return answerInner(convoId, userAnswer);
        } finally {
            lock.unlock();
        }
    }

    private AnswerResponse answerInner(String convoId, String userAnswer) {
        Conversation convo = convoCache.get(convoId);
        if (convo == null) {
            throw new IllegalArgumentException("对话不存在");
        }

        ModelConfig modelConfig = extractModelConfig(convo);
        String now = LocalDateTime.now().format(DTF);

        // 获取当前问题
        String currentQuestion = convo.getMessages().stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsQuestion()))
                .reduce((first, second) -> second)
                .map(Message::getContent)
                .orElse("");

        // ===== 评分（使用 EvaluatorAgent + RAG）=====
        String candidateProfile = buildProfileSummary(convo.getCandidateProfile(),
                getSetting(convo, "position", "软件工程师"),
                getSetting(convo, "experience", ""));

        // RAG 检索参考答案（如果向量库已配置）
        String retrievedContext = "";
        if (vectorStoreService.isAvailable()) {
            try {
                retrievedContext = vectorStoreService.retrieveReferenceAnswer(currentQuestion, 2);
            } catch (Exception e) {
                log.warn("RAG 检索失败，不影响主流程: {}", e.getMessage());
            }
        }

        // 如果有简历技术栈，从向量库检索技能相关题目作为技术面出题指导
        String skillQuestionsContext = null;
        if (convo.getCandidateProfile() != null
                && convo.getCandidateProfile().getTechStack() != null
                && !convo.getCandidateProfile().getTechStack().isEmpty()) {
            try {
                skillQuestionsContext = vectorStoreService.retrieveSkillQuestions(
                        convo.getCandidateProfile().getTechStack(), 5);
            } catch (Exception e) {
                log.warn("检索技能相关题目失败: {}", e.getMessage());
            }
        }

        EvaluatorAgent.EvaluationResult evalResult = evaluatorAgent.evaluate(
                currentQuestion,
                userAnswer,
                modelConfig,
                candidateProfile,
                retrievedContext
        );

        // 添加用户回答消息
        convo.getMessages().add(Message.builder()
                .role("user")
                .content(userAnswer)
                .timestamp(now)
                .score(evalResult.score())
                .feedback(evalResult.feedback())
                .modelAnswer(evalResult.modelAnswer())
                .build());

        // 更新面试阶段
        int questionCount = convo.getCurrentQuestionIndex() != null ? convo.getCurrentQuestionIndex() : 0;
        InterviewPhase currentPhase = parsePhase(convo.getInterviewPhase());
        InterviewPhase nextPhase = interviewerAgent.nextPhase(currentPhase, questionCount + 1);
        convo.setInterviewPhase(nextPhase.name());

        // ===== 判断是否结束 =====
        boolean shouldEnd = interviewerAgent.shouldEndInterview(
                convo.getMessages(),
                getSetting(convo, "position", "软件工程师"),
                questionCount,
                modelConfig
        );

        String nextQuestion = null;

        if (shouldEnd) {
            // 生成结束语
            String closing = interviewerAgent.generateClosingMessage(
                    getSetting(convo, "position", ""),
                    getSetting(convo, "company", ""),
                    modelConfig
            );
            convo.getMessages().add(Message.builder()
                    .role("interviewer")
                    .content(closing)
                    .timestamp(LocalDateTime.now().format(DTF))
                    .build());
            convo.setStatus("completed");
            log.info("面试 [{}] 已结束", convoId);
        } else {
            // 生成下一题
            nextQuestion = interviewerAgent.generateQuestion(
                    candidateProfile,
                    getSetting(convo, "position", "软件工程师"),
                    getSetting(convo, "experience", ""),
                    nextPhase,
                    questionCount + 1,
                    convo.getMessages(),
                    modelConfig,
                    skillQuestionsContext
            );

            convo.getMessages().add(Message.builder()
                    .role("interviewer")
                    .content(nextQuestion)
                    .timestamp(LocalDateTime.now().format(DTF))
                    .isQuestion(true)
                    .build());

            convo.setCurrentQuestionIndex(questionCount + 1);
        }

        convo.setUpdatedAt(LocalDateTime.now().format(DTF));

        // 持久化（已持有锁，直接写）
        saveConversations(convoCache.values().stream().toList());

        // 构建返回（含分维度评分）
        AnswerResponse.DimensionScores dimScores = AnswerResponse.DimensionScores.builder()
                .technicalDepth(evalResult.dimensionScores().technicalDepth())
                .expressionClarity(evalResult.dimensionScores().expressionClarity())
                .logicCoherence(evalResult.dimensionScores().logicCoherence())
                .experienceRelevance(evalResult.dimensionScores().experienceRelevance())
                .build();

        AnswerResponse.EvaluationResult dtoEval = AnswerResponse.EvaluationResult.builder()
                .score(evalResult.score())
                .feedback(evalResult.feedback())
                .modelAnswer(evalResult.modelAnswer())
                .dimensionScores(dimScores)
                .build();

        return AnswerResponse.builder()
                .evaluation(dtoEval)
                .nextQuestion(nextQuestion)
                .isFinished("completed".equals(convo.getStatus()))
                .messages(convo.getMessages())
                .build();
    }

    // ============ 会话控制 ============

    public void stopConversation(String convoId, List<Message> messages, String status) {
        ReentrantLock lock = lockFor(convoId);
        lock.lock();
        try {
            Conversation convo = convoCache.get(convoId);
            if (convo != null) {
                convo.setMessages(messages);
                convo.setStatus(status != null ? status : "stopped");
                convo.setUpdatedAt(LocalDateTime.now().format(DTF));
                saveConversations(convoCache.values().stream().toList());
            }
        } finally {
            lock.unlock();
        }
    }

    public void deleteConversation(String convoId) {
        ReentrantLock lock = lockFor(convoId);
        lock.lock();
        try {
            // 删除向量数据（如果向量库已配置）
            if (vectorStoreService.isAvailable()) {
                try {
                    vectorStoreService.deleteResume(convoId);
                } catch (Exception e) {
                    log.warn("删除向量数据失败: {}", convoId);
                }
            }
            convoCache.remove(convoId);
            cacheVersion++;
            saveConversations(convoCache.values().stream().toList());
        } finally {
            lock.unlock();
        }
    }

    // ============ 工具方法 ============

    private String buildProfileSummary(CandidateProfile profile, String position, String experience) {
        if (profile == null) {
            return String.format("应聘 %s 岗位（要求 %s 经验），暂无简历信息。",
                    position, experience);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("姓名: ").append(profile.getName() != null ? profile.getName() : "未知").append("\n");

        if (profile.getEducation() != null && !profile.getEducation().isBlank()) {
            sb.append("学历: ").append(profile.getEducation()).append("\n");
        }
        if (profile.getWorkExperience() != null && !profile.getWorkExperience().isBlank()) {
            sb.append("工作年限: ").append(profile.getWorkExperience()).append("\n");
        }

        if (profile.getTechStack() != null && !profile.getTechStack().isEmpty()) {
            sb.append("技术栈: ").append(String.join(" / ", profile.getTechStack())).append("\n");
        }

        if (profile.getWorkHistory() != null && !profile.getWorkHistory().isEmpty()) {
            sb.append("工作经历:\n");
            profile.getWorkHistory().forEach(w -> sb.append("  - ").append(w).append("\n"));
        }

        if (profile.getProjectHistory() != null && !profile.getProjectHistory().isEmpty()) {
            sb.append("项目经历:\n");
            profile.getProjectHistory().forEach(p -> sb.append("  - ").append(p).append("\n"));
        }

        if (profile.getProfileSummary() != null && !profile.getProfileSummary().isBlank()) {
            sb.append("整体画像: ").append(profile.getProfileSummary());
        }

        return sb.toString();
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
            log.error("解析 modelConfig 失败", e);
            return ModelConfig.builder().type("openai").build();
        }
    }

    private String getSetting(Conversation convo, String key, String defaultValue) {
        Object val = convo.getSettings().get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private InterviewPhase parsePhase(String phase) {
        try {
            return InterviewPhase.valueOf(phase);
        } catch (Exception e) {
            return InterviewPhase.OPENING;
        }
    }

    @SuppressWarnings("unchecked")
    private void saveConversations(List<Conversation> convos) {
        // 更新缓存
        convoCache.clear();
        for (Conversation c : convos) {
            convoCache.put(c.getId(), c);
        }
        cacheVersion++;

        // 持久化到磁盘
        List<Map<String, Object>> data = convos.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        jsonFileUtil.writeJson(CONVOS_FILE, data);
    }

    /**
     * 启动时从磁盘加载缓存，解决服务重启后缓存为空的问题
     */
    @jakarta.annotation.PostConstruct
    public void initCache() {
        List<Conversation> all = jsonFileUtil.readJsonList(CONVOS_FILE, Conversation.class, List.of());
        convoCache.clear();
        for (Conversation c : all) {
            convoCache.put(c.getId(), c);
        }
        log.info("[ConversationService] 已从磁盘加载 {} 条会话到缓存", convoCache.size());
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
        map.put("candidateProfile", c.getCandidateProfile());
        map.put("interviewPhase", c.getInterviewPhase());
        return map;
    }
}
