package com.interview.assistant.agent;

import com.interview.assistant.config.QdrantConfig;
import com.interview.assistant.model.AppSettings.ModelConfig;
import com.interview.assistant.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Interviewer Agent（面试官 Agent）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewerAgent {

    private final QdrantConfig qdrantConfig;

    public enum InterviewPhase {
        OPENING, TECHNICAL, BEHAVIORAL, DEEP_DIVE, WRAP_UP
    }

    public String generateQuestion(
            String candidateProfile,
            String position,
            String experience,
            InterviewPhase currentPhase,
            int questionIndex,
            List<Message> conversationHistory,
            ModelConfig modelConfig
    ) {
        String systemPrompt = buildSystemPrompt(position, experience, candidateProfile, currentPhase);
        String userPrompt = buildQuestionPrompt(questionIndex, conversationHistory, currentPhase);

        String result = qdrantConfig.callLlm(
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                modelConfig.getApiKey(),
                modelConfig.getBaseUrl(),
                modelConfig.getModel(),
                0.7
        );

        return result != null ? cleanOutput(result) : "请介绍一下你最近做的最有挑战性的项目是什么？";
    }

    public boolean shouldEndInterview(
            List<Message> conversationHistory,
            String position,
            int questionCount,
            ModelConfig modelConfig
    ) {
        if (questionCount >= 10) return true;
        if (questionCount < 3) return false;

        String historySummary = buildHistorySummary(conversationHistory, questionCount);
        String prompt = """
                这是一个 %s 岗位的面试。

                【面试历史摘要】
                %s

                目前已问了 %d 道问题。

                基于以上对话内容，客观判断：
                - 面试是否已经充分评估了候选人的核心技术能力？
                - 是否还有关键维度没有考察到？

                请直接回答"结束"或"继续"，不要加其他内容，不要加标点符号。
                """.formatted(position, historySummary, questionCount);

        String result = qdrantConfig.callLlm(
                List.of(Map.of("role", "system", "content", "你是一位专业的面试官，判断要客观公正。"),
                        Map.of("role", "user", "content", prompt)),
                modelConfig.getApiKey(), modelConfig.getBaseUrl(), modelConfig.getModel(), 0.0
        );

        if (result == null) return questionCount >= 10;
        boolean shouldEnd = result.trim().toLowerCase().contains("结束");
        log.info("面试结束判断: {} (questionCount={})", shouldEnd ? "结束" : "继续", questionCount);
        return shouldEnd;
    }

    public String generateClosingMessage(String position, String company, ModelConfig modelConfig) {
        String prompt = """
                作为 %s 的面试官，请为一位应聘 %s 岗位的候选人生成一段简短的面试结束语。

                要求：
                1. 感谢候选人的参与
                2. 告知面试结束
                3. 说明后续流程
                4. 保持专业、友善

                直接输出结束语，不要加前缀。
                """.formatted(company, position);

        String result = qdrantConfig.callLlm(
                List.of(Map.of("role", "system", "content", "你是一位专业、友善的面试官。"),
                        Map.of("role", "user", "content", prompt)),
                modelConfig.getApiKey(), modelConfig.getBaseUrl(), modelConfig.getModel(), 0.7
        );

        return result != null ? cleanOutput(result)
                : "非常感谢你的参与，今天的面试到此结束。我们会在一周内通知你结果。祝你好运！";
    }

    public InterviewPhase nextPhase(InterviewPhase current, int questionCount) {
        if (questionCount <= 1) return InterviewPhase.OPENING;
        if (questionCount <= 3) return InterviewPhase.TECHNICAL;
        if (questionCount <= 6) return InterviewPhase.BEHAVIORAL;
        if (questionCount <= 9) return InterviewPhase.DEEP_DIVE;
        return InterviewPhase.WRAP_UP;
    }

    private String buildSystemPrompt(String position, String experience, String candidateProfile, InterviewPhase phase) {
        String phaseDesc = switch (phase) {
            case OPENING -> "开场阶段，问一些建立 rapport 的问题，如自我介绍、项目经历概述";
            case TECHNICAL -> "技术深入阶段，结合简历问具体的技术问题，考察真实能力";
            case BEHAVIORAL -> "行为面试阶段，问一些场景题，考察问题解决能力和思维方式";
            case DEEP_DIVE -> "深挖阶段，针对简历中的亮点项目或存疑点深入追问";
            case WRAP_UP -> "收尾阶段，可以问一些开放性问题";
        };

        return """
                你是一位专业、严谨且友善的面试官，正在面试一位应聘 %s 岗位（要求 %s 经验）的候选人。

                【当前阶段】%s

                【候选人背景】
                %s

                面试要求：
                - 问题必须基于候选人的实际简历经历，不能问泛泛的八股文
                - 技术问题要问到具体的实现细节、原理、踩坑经验
                - 保持友好专业的面试氛围
                """.formatted(position, experience, phaseDesc, candidateProfile);
    }

    private String buildQuestionPrompt(int questionIndex, List<Message> history, InterviewPhase phase) {
        String historyText = buildHistorySummary(history, questionIndex);

        String phaseInstruction = switch (phase) {
            case OPENING -> "问一道开场问题，可以从候选人的项目经历中挑一个最亮眼的让他介绍。";
            case TECHNICAL -> "基于候选人的技术栈，问一个深入的技术问题，要求他回答具体实现细节。";
            case BEHAVIORAL -> "结合候选人简历中的经历，问一个行为面试场景题（STAR 法则）。";
            case DEEP_DIVE -> "针对简历中描述不够清晰的地方，或者可以深挖的技术亮点，进行追问。";
            case WRAP_UP -> "问一个开放性问题，给候选人展示综合素质的机会。";
        };

        return """
                这是第 %d 道面试问题。

                【面试历史】
                %s

                【出题要求】
                %s

                【重要约束】
                - 禁止重复问与历史问题相同或高度相似的问题
                - 必须基于候选人简历中尚未被深入讨论的的经历来提问
                - 每道问题要有明显的角度差异

                只输出问题本身，不要加"面试官："、"问题："等前缀，不要加引号。
                """.formatted(questionIndex, historyText, phaseInstruction);
    }

    private String buildHistorySummary(List<Message> history, int questionCount) {
        if (history == null || history.isEmpty()) return "（暂无历史记录）";

        StringBuilder sb = new StringBuilder();
        int qNum = 0;
        for (Message msg : history) {
            if ("interviewer".equals(msg.getRole()) && Boolean.TRUE.equals(msg.getIsQuestion())) {
                qNum++;
                String q = msg.getContent().length() > 200
                        ? msg.getContent().substring(0, 200) + "..."
                        : msg.getContent();
                sb.append(String.format("Q%d: %s%n", qNum, q));
            } else if ("user".equals(msg.getRole())) {
                String preview = msg.getContent().length() > 200
                        ? msg.getContent().substring(0, 200) + "..."
                        : msg.getContent();
                sb.append(String.format("A%d: %s%n", qNum, preview));
            }
        }
        String summary = sb.toString();
        if (qNum > 0) {
            summary += "\n【已问过的题目方向】请勿从以上题目或其变体中选取，确保每道题有全新角度。\n";
        }
        return summary;
    }

    private String cleanOutput(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        String[] prefixes = {"面试官：", "面试官:", "问题：", "问题:", "Q:", "Q:"};
        for (String prefix : prefixes) {
            if (trimmed.startsWith(prefix)) {
                trimmed = trimmed.substring(prefix.length()).trim();
            }
        }
        return trimmed;
    }
}
