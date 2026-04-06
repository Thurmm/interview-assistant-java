package com.interview.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.config.QdrantConfig;
import com.interview.assistant.dto.ResumeResponse;
import com.interview.assistant.model.AppSettings.ModelConfig;
import com.interview.assistant.service.DocumentParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resume Agent（简历解析 Agent）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeAgent {

    private final QdrantConfig qdrantConfig;
    private final ObjectMapper objectMapper;
    private final DocumentParserService documentParserService;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?86)?[1][3-9]\\d{9}");

    public ResumeResponse parseAndStore(
            byte[] resumeBytes,
            String filename,
            String candidateId,
            ModelConfig modelConfig
    ) {
        String rawText;
        try {
            rawText = documentParserService.parse(resumeBytes, filename);
        } catch (Exception e) {
            log.error("简历解析失败: {}", filename, e);
            return ResumeResponse.builder().success(false)
                    .errorMessage("简历解析失败: " + e.getMessage()).build();
        }

        if (rawText == null || rawText.isBlank()) {
            return ResumeResponse.builder().success(false)
                    .errorMessage("简历内容为空或无法提取文本").build();
        }

        log.info("简历解析成功，文本长度: {} 字符", rawText.length());

        ResumeResponse profile = analyzeResume(rawText, modelConfig);

        // 向量存储（内存模式，跳过或简单存储）
        try {
            // VectorStoreService.storeResume(candidateId, rawText, ...);
        } catch (Exception e) {
            log.warn("简历存储跳过: {}", e.getMessage());
        }

        profile.setRawText(rawText);
        profile.setSuccess(true);
        return profile;
    }

    private ResumeResponse analyzeResume(String rawText, ModelConfig modelConfig) {
        String prompt = """
                请分析以下简历，提取关键信息并生成候选人画像。

                简历内容：
                %s

                请用 JSON 格式返回，不要加任何前缀后缀，不要用 markdown 代码块：
                {
                    "name": "候选人姓名（未找到填"未知"）",
                    "email": "邮箱（未找到填"未知"）",
                    "phone": "手机号（未找到填"未知"）",
                    "education": "最高学历（如：本科 - 计算机科学）",
                    "work_experience": "工作年限（如：3年"或"5年+"）",
                    "tech_stack": ["技术栈1", "技术栈2", ...],
                    "work_history": ["公司名称 - 职位 - 时间段（摘要）", ...],
                    "project_history": ["项目名称 - 项目描述（1-2句话）", ...],
                    "profile_summary": "一段话概括候选人：200字左右的整体画像描述"
                }
                """.formatted(rawText);

        String result = qdrantConfig.callLlm(
                List.of(
                        Map.of("role", "system", "content", "你是专业的简历分析专家，提取信息要准确，画像描述要客观真实。"),
                        Map.of("role", "user", "content", prompt)
                ),
                modelConfig.getApiKey(),
                modelConfig.getBaseUrl(),
                modelConfig.getModel(),
                0.3
        );

        if (result == null) {
            return ResumeResponse.builder()
                    .name("未知").success(false)
                    .errorMessage("LLM 分析失败").build();
        }

        return parseProfileResult(result);
    }

    private ResumeResponse parseProfileResult(String content) {
        try {
            String jsonStr = cleanOutput(content);
            JsonNode node = objectMapper.readTree(jsonStr);

            List<String> techStack = new ArrayList<>();
            if (node.has("tech_stack") && node.get("tech_stack").isArray()) {
                node.get("tech_stack").forEach(t -> techStack.add(t.asText()));
            }

            List<String> workHistory = new ArrayList<>();
            if (node.has("work_history") && node.get("work_history").isArray()) {
                node.get("work_history").forEach(w -> workHistory.add(w.asText()));
            }

            List<String> projectHistory = new ArrayList<>();
            if (node.has("project_history") && node.get("project_history").isArray()) {
                node.get("project_history").forEach(p -> projectHistory.add(p.asText()));
            }

            return ResumeResponse.builder()
                    .name(node.has("name") ? node.get("name").asText("未知") : "未知")
                    .email(extractField(node, "email", content))
                    .phone(extractField(node, "phone", content))
                    .education(node.has("education") ? node.get("education").asText("") : "")
                    .workExperience(node.has("work_experience") ? node.get("work_experience").asText("") : "")
                    .techStack(techStack)
                    .workHistory(workHistory)
                    .projectHistory(projectHistory)
                    .profileSummary(node.has("profile_summary") ? node.get("profile_summary").asText("") : "")
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("解析画像 JSON 失败: {}", content, e);
            return ResumeResponse.builder().name("未知").success(false)
                    .errorMessage("解析画像 JSON 失败").build();
        }
    }

    private String extractField(JsonNode node, String field, String rawContent) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        if ("email".equals(field)) {
            Matcher m = EMAIL_PATTERN.matcher(rawContent);
            return m.find() ? m.group() : "";
        }
        if ("phone".equals(field)) {
            Matcher m = PHONE_PATTERN.matcher(rawContent);
            return m.find() ? m.group() : "";
        }
        return "";
    }

    private String cleanOutput(String content) {
        if (content == null) return "{}";
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7);
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3);
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        return trimmed.trim();
    }
}
