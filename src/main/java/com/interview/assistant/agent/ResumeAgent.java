package com.interview.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.dto.ResumeResponse;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.service.DocumentParserService;
import com.interview.assistant.service.LlmService;
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
public class ResumeAgent {

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final DocumentParserService documentParserService;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?86)?[1][3-9]\\d{9}");

    public ResumeAgent(LlmService llmService, DocumentParserService documentParserService) {
        this.llmService = llmService;
        this.documentParserService = documentParserService;
        this.objectMapper = new ObjectMapper();
    }

    public ResumeResponse parseAndStore(
            byte[] resumeBytes,
            String filename,
            String candidateId,
            AppSettings.ModelConfig modelConfig
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
        profile.setRawText(rawText);

        return profile;
    }

    private ResumeResponse analyzeResume(String rawText, AppSettings.ModelConfig modelConfig) {
        String prompt = """
                你是一个专业的简历分析专家。请从以下简历中提取信息，返回结构化的 JSON。

                简历内容：
                %s

                提取要求：
                1. name：候选人姓名，精确提取，不要编造。
                2. email：邮箱，精确提取。
                3. phone：手机号，精确提取。
                4. education：最高学历，格式如"本科 - 计算机科学"，学校名+专业名。
                5. work_experience：工作年限，格式如"3年"、"5年+"、"应届生"。
                6. tech_stack：技术栈列表，只需提取实际掌握的技术，不要泛泛写"熟练使用Spring"，要具体如"Spring Boot / Spring MVC / MyBatis / Redis / MySQL / Docker"。
                7. work_history：工作经历，每条格式"公司名 | 职位 | 时间段 | 主要职责摘要"，时间格式统一如"2021.09-2023.06"。
                8. project_history：项目经历，每个项目是一个对象，格式如：{"project_name":"项目名称","role":"项目角色（候选人担当什么）","tech_stack":"本项目使用的技术栈","description":"1-2句话项目描述，说明项目目标、你的核心贡献和成果"}。数组形式，如：[{"project_name":"...","role":"...","tech_stack":"...","description":"..."}]。
                9. profile_summary：200字左右的候选人画像描述，包含技术深度、擅长方向、代表性成就。

                注意事项：
                - 只提取简历中明确提到的内容，不要推测或补全缺失信息。
                - tech_stack 要拆散，不要写"全栈开发"这种笼统的词。
                - work_history 和 project_history 要分开，有项目经历就从项目中提取项目信息。
                - 如果某字段找不到，填"未提供"。

                返回格式：直接返回纯 JSON 文本，不包含 markdown 代码块。
                {"""
                .formatted(rawText);

        String systemPrompt = "你是一个专业的简历分析专家。严格根据简历内容提取信息，不要编造。返回的 JSON 必须是合法的纯文本格式，不包含 markdown 代码块。";

        LlmService.LlmResult llmResult = null;
        String lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            llmResult = llmService.callLlmWithRaw(
                    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", prompt)
                    ),
                    modelConfig
            );
            if (llmResult.isSuccess() && llmResult.getContent() != null && !llmResult.getContent().isBlank()) {
                break;
            }
            lastError = llmResult.getErrorMessage() != null ? llmResult.getErrorMessage()
                    : (llmResult.getContent() != null ? "LLM 返回内容为空" : "LLM 调用失败");
            log.warn("[ResumeAgent] 第 {} 次尝试失败: {}", attempt, lastError);
            if (attempt < 3) {
                try { Thread.sleep(attempt * 2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        if (llmResult == null || !llmResult.isSuccess() || llmResult.getContent() == null || llmResult.getContent().isBlank()) {
            String errDetail = lastError != null ? lastError
                    : (llmResult != null && llmResult.getRawResponse() != null
                            ? llmResult.getRawResponse().substring(0, Math.min(200, llmResult.getRawResponse().length()))
                            : "未知错误");
            return ResumeResponse.builder()
                    .name("未知").success(false)
                    .errorMessage("LLM 调用失败（已重试 3 次）: " + errDetail)
                    .llmRawResponse(llmResult != null ? llmResult.getRawResponse() : null)
                    .build();
        }

        log.info("[ResumeAgent] LLM 原始返回内容: {}", llmResult.getContent());
        ResumeResponse profile = parseProfileResult(llmResult.getContent(), rawText);
        if (!profile.isSuccess()) {
            return ResumeResponse.builder()
                    .name("未知").success(false)
                    .errorMessage("LLM 返回格式异常，无法解析: " + profile.getErrorMessage()
                            + "。原始返回: " + llmResult.getContent().substring(0, Math.min(200, llmResult.getContent().length())))
                    .llmRawResponse(llmResult.getRawResponse())
                    .build();
        }
        profile.setLlmRawResponse(llmResult.getRawResponse());

        log.info("[ResumeAgent] 解析结果: name={}, techSize={}, workSize={}, projectSize={}, profile={}",
                profile.getName(),
                profile.getTechStack() != null ? profile.getTechStack().size() : 0,
                profile.getWorkHistory() != null ? profile.getWorkHistory().size() : 0,
                profile.getProjectHistory() != null ? profile.getProjectHistory().size() : 0,
                profile.getProfileSummary() != null ? profile.getProfileSummary().substring(0, Math.min(80, profile.getProfileSummary().length())) : "无");

        return profile;
    }

    /**
     * 从 LLM 返回内容中解析出完整的候选人画像。
     * LLM 有时会返回多个 JSON 块（第一个是详细版 snake_case，第二个是摘要版 camelCase），
     * 这里尝试找出包含 tech_stack/project_history 等字段的完整版本来解析。
     */
    private ResumeResponse parseProfileResult(String llmRaw, String rawText) {
        try {
            List<JsonNode> allNodes = new ArrayList<>();

            // 去掉 markdown 代码块，提取内容
            String content = llmRaw;
            content = content.replaceAll("```json\\s*", "");
            content = content.replaceAll("```\\s*", "");
            content = content.replaceAll("```", "").trim();

            // 尝试把整个内容解析为一个 JSON
            try {
                allNodes.add(objectMapper.readTree(content));
            } catch (Exception ignored) {}

            // 尝试用正则找所有 JSON 对象（处理 LLM 返回多个 JSON 的情况）
            Pattern jsonObjPat = Pattern.compile("\\{[^{}]*\\}");
            Matcher jsonMatcher = jsonObjPat.matcher(content);
            while (jsonMatcher.find()) {
                String json片段 = jsonMatcher.group();
                try {
                    JsonNode n = objectMapper.readTree(json片段);
                    // 避免重复
                    boolean seen = allNodes.stream().anyMatch(existing -> existing.equals(n));
                    if (!seen) allNodes.add(n);
                } catch (Exception ignored) {}
            }

            // 打印所有找到的 JSON key 列表（方便调试）
            for (int i = 0; i < allNodes.size(); i++) {
                JsonNode n = allNodes.get(i);
                java.util.Iterator<String> fn = n.fieldNames();
                java.util.List<String> keys = new java.util.ArrayList<>();
                fn.forEachRemaining(keys::add);
                log.info("[ResumeAgent] JSON #{} top-level keys: {}", i, keys);
            }

            // 优先选择 snake_case 版本（有 tech_stack/project_history 字段）
            JsonNode node = null;
            for (JsonNode n : allNodes) {
                if (n.has("tech_stack") || n.has("project_history") || n.has("work_history")) {
                    node = n;
                    log.info("[ResumeAgent] 选用 JSON with snake_case fields (index={})", allNodes.indexOf(n));
                    break;
                }
            }
            if (node == null && !allNodes.isEmpty()) {
                node = allNodes.get(0);
                log.info("[ResumeAgent] 未找到 snake_case JSON，fallback 到第一个");
            }

            if (node == null) {
                return ResumeResponse.builder().name("未知").success(false)
                        .errorMessage("无法从 LLM 返回中解析出有效 JSON，内容: " + llmRaw.substring(0, Math.min(200, llmRaw.length()))).build();
            }

            List<String> techStack = new ArrayList<>();
            if (node.has("tech_stack") && node.get("tech_stack").isArray()) {
                node.get("tech_stack").forEach(t -> techStack.add(t.asText()));
            }

            List<String> workHistory = new ArrayList<>();
            if (node.has("work_history") && node.get("work_history").isArray()) {
                node.get("work_history").forEach(w -> workHistory.add(w.asText()));
            }

            List<Map<String, String>> projectHistory = new ArrayList<>();
            if (node.has("project_history") && node.get("project_history").isArray()) {
                node.get("project_history").forEach(p -> {
                    if (p.isObject()) {
                        Map<String, String> proj = new java.util.LinkedHashMap<>();
                        proj.put("project_name", p.has("project_name") ? p.get("project_name").asText("") : "");
                        proj.put("role", p.has("role") ? p.get("role").asText("") : "");
                        proj.put("tech_stack", p.has("tech_stack") ? p.get("tech_stack").asText("") : "");
                        proj.put("description", p.has("description") ? p.get("description").asText("") : "");
                        projectHistory.add(proj);
                    } else if (p.isTextual()) {
                        Map<String, String> proj = new java.util.LinkedHashMap<>();
                        proj.put("description", p.asText());
                        projectHistory.add(proj);
                    }
                });
            }

            return ResumeResponse.builder()
                    .llmRawResponse(llmRaw)
                    .name(node.has("name") ? node.get("name").asText("未知") : "未知")
                    .email(extractField(node, "email", rawText))
                    .phone(extractField(node, "phone", rawText))
                    .education(node.has("education") ? node.get("education").asText("") : "")
                    .workExperience(node.has("work_experience") ? node.get("work_experience").asText("") : "")
                    .techStack(techStack)
                    .workHistory(workHistory)
                    .projectHistory(projectHistory)
                    .profileSummary(node.has("profile_summary") ? node.get("profile_summary").asText("") : "")
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("解析画像 JSON 失败: {}", llmRaw, e);
            return ResumeResponse.builder().name("未知").success(false)
                    .errorMessage("解析画像 JSON 失败: " + e.getMessage()).build();
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
}
