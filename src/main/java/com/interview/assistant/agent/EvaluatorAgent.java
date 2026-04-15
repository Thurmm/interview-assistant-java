package com.interview.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.config.QdrantConfig;
import com.interview.assistant.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Evaluator Agent（评估 Agent）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluatorAgent {

    private final QdrantConfig qdrantConfig;
    private final ObjectMapper objectMapper;

    public record EvaluationResult(
            int score,
            String feedback,
            String modelAnswer,
            DimensionScores dimensionScores
    ) {}

    public record DimensionScores(
            int technicalDepth,
            int expressionClarity,
            int logicCoherence,
            int experienceRelevance
    ) {}

    public EvaluationResult evaluate(
            String question,
            String answer,
            AppSettings.ModelConfig modelConfig,
            String candidateProfile,
            String retrievedContext
    ) {
        if (answer == null || answer.isBlank()) {
            return new EvaluationResult(0, "未检测到有效回答", "请提供一个有效的回答",
                    new DimensionScores(0, 0, 0, 0));
        }

        String prompt = buildEvaluationPrompt(question, answer, candidateProfile, retrievedContext);

        String result = qdrantConfig.callLlm(
                List.of(
                        Map.of("role", "system", "content", "你是一位专业的面试官评估专家，评估要客观公正，分数要合理分布。"),
                        Map.of("role", "user", "content", prompt)
                ),
                modelConfig.getApiKey(),
                modelConfig.getBaseUrl(),
                modelConfig.getModel(),
                0.7
        );

        if (result == null) {
            return new EvaluationResult(5, "评估服务暂时不可用，回答已记录",
                    "建议从实际项目经验出发，结合具体案例来回答会更好。",
                    new DimensionScores(5, 5, 5, 5));
        }

        return parseEvaluationResult(result);
    }

    private String buildEvaluationPrompt(String question, String answer, String candidateProfile, String retrievedContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的面试官，正在评估候选人的回答。\n\n");

        if (candidateProfile != null && !candidateProfile.isBlank()) {
            sb.append("【候选人背景】\n").append(candidateProfile).append("\n\n");
        }
        if (retrievedContext != null && !retrievedContext.isBlank()) {
            sb.append("【参考材料】\n").append(retrievedContext).append("\n\n");
        }

        sb.append("【面试问题】\n").append(question).append("\n\n");
        sb.append("【候选人回答】\n").append(answer).append("\n\n");

        sb.append("""
            请从以下四个维度对回答进行评估（每项 0-10 分）：

            1. technical_depth（技术深度）: 回答是否展现了足够的技术深度和理解？
            2. expression_clarity（表达清晰度）: 回答是否条理清晰、易于理解？
            3. logic_coherence（逻辑连贯性）: 回答是否前后一致、逻辑自洽？
            4. experience_relevance（经验相关性）: 回答是否贴合候选人实际经历？

            请用 JSON 格式返回评估结果：
            {
                "overall_score": 7,
                "technical_depth": 7,
                "expression_clarity": 8,
                "logic_coherence": 7,
                "experience_relevance": 6,
                "feedback": "回答较为完整，但技术深度有待加强...",
                "model_answer": "更好的回答范例：..."
            }
            """);

        return sb.toString();
    }

    private EvaluationResult parseEvaluationResult(String content) {
        if (content == null || content.isBlank()) {
            log.warn("评估内容为空，返回默认结果");
            return defaultResult("评估内容为空");
        }

        try {
            // 先去除 think 标签（MiniMax 思考过程）
            String cleaned = content
                    .replaceAll("(?s)<\\|im_start\\|>think.*?<\\|STOP\\|>", "")
                    .replaceAll("(?s)<\\|im_end\\|>", "")
                    .replaceAll("(?s)<\\|STOP\\|>", "")
                    .replaceAll("(?s)<start_of_thought>.*?<end_of_thought>", "")
                    .replaceAll("(?s)<think>.*?<\\/think>", "")
                    .trim();

            // 尝试找 JSON 块
            String jsonStr = extractJson(cleaned);
            if (jsonStr == null) {
                log.warn("无法从评估结果中提取 JSON: {}", cleaned.substring(0, Math.min(100, cleaned.length())));
                return defaultResult("无法解析评估结果");
            }

            JsonNode node = objectMapper.readTree(jsonStr);

            int overallScore = safeGetInt(node, "overall_score", 5);
            overallScore = Math.max(0, Math.min(10, overallScore));

            String feedback = node.has("feedback") && !node.get("feedback").isNull()
                    ? node.get("feedback").asText() : "回答已记录";
            String modelAnswer = node.has("model_answer") && !node.get("model_answer").isNull()
                    ? node.get("model_answer").asText() : "建议结合具体项目经验来回答会更好。";

            log.info("评估成功: score={}, feedback={}", overallScore, feedback.substring(0, Math.min(30, feedback.length())));

            return new EvaluationResult(
                    overallScore,
                    feedback,
                    modelAnswer,
                    new DimensionScores(
                            safeGetInt(node, "technical_depth", overallScore),
                            safeGetInt(node, "expression_clarity", overallScore),
                            safeGetInt(node, "logic_coherence", overallScore),
                            safeGetInt(node, "experience_relevance", overallScore)
                    )
            );
        } catch (Exception e) {
            log.error("解析评估结果 JSON 失败: {}", content.substring(0, Math.min(200, content.length())), e);
            return defaultResult("评估解析异常");
        }
    }

    private EvaluationResult defaultResult(String reason) {
        return new EvaluationResult(5, "[" + reason + "] 回答已记录，建议结合实际项目经验详细描述。",
                "建议从项目背景、技术选型、遇到的问题和解决方案等角度展开回答。",
                new DimensionScores(5, 5, 5, 5));
    }

    /**
     * 从文本中提取 JSON 对象（找第一个 { 到最后一个 }）
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int end = text.lastIndexOf('}');
        if (end <= start) return null;
        return text.substring(start, end + 1);
    }

    private int safeGetInt(JsonNode node, String field, int defaultVal) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asInt(defaultVal);
        }
        return defaultVal;
    }

    private String cleanJsonWrapper(String content) {
        if (content == null) return "{}";
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7);
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3);
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        return trimmed.trim();
    }
}
