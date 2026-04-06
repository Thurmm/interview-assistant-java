package com.interview.assistant.controller;

import com.interview.assistant.agent.ResumeAgent;
import com.interview.assistant.dto.ResumeResponse;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * 简历上传 Controller
 *
 * 支持：
 * - PDF 简历上传
 * - DOCX 简历上传
 * - TXT 简历上传（兜底）
 *
 * 流程：
 * 1. 上传简历 → ResumeAgent 解析 → 生成候选人画像
 * 2. 画像存储到 Conversation.settings 中，供后续出题使用
 */
@Slf4j
@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ResumeController {

    private final ResumeAgent resumeAgent;
    private final SettingsService settingsService;

    /**
     * 上传简历并解析
     *
     * POST /api/resume/upload
     *
     * @param file 简历文件（PDF/DOCX/TXT）
     * @return 解析结果（候选人画像）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadResume(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件名无效"));
        }

        // 限制文件大小 10MB
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件大小不能超过 10MB"));
        }

        // 限制格式
        String lowerName = filename.toLowerCase();
        if (!lowerName.endsWith(".pdf") && !lowerName.endsWith(".docx") && !lowerName.endsWith(".txt")) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "仅支持 PDF、DOCX、TXT 格式")
            );
        }

        AppSettings settings = settingsService.getSettings();
        AppSettings.ModelConfig modelConfig = settings.getModelConfig();

        if (modelConfig == null || modelConfig.getApiKey() == null || modelConfig.getApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "请先在设置中配置大模型 API Key"
            ));
        }

        try {
            String candidateId = UUID.randomUUID().toString().substring(0, 8);

            ResumeResponse result = resumeAgent.parseAndStore(
                    file.getBytes(),
                    filename,
                    candidateId,
                    modelConfig
            );

            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", result.getErrorMessage()
                ));
            }

            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("candidateId", candidateId);
            response.put("name", result.getName());
            response.put("email", result.getEmail());
            response.put("phone", result.getPhone());
            response.put("education", result.getEducation());
            response.put("workExperience", result.getWorkExperience());
            response.put("techStack", result.getTechStack());
            response.put("workHistory", result.getWorkHistory());
            response.put("projectHistory", result.getProjectHistory());
            response.put("profileSummary", result.getProfileSummary());
            response.put("message", "简历解析成功！");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("简历上传失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "简历处理失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 测试简历解析（不存储向量，仅解析）
     *
     * POST /api/resume/parse
     */
    @PostMapping("/parse")
    public ResponseEntity<?> parseResume(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        AppSettings settings = settingsService.getSettings();
        AppSettings.ModelConfig modelConfig = settings.getModelConfig();

        if (modelConfig == null || modelConfig.getApiKey() == null || modelConfig.getApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "请先在设置中配置大模型 API Key"
            ));
        }

        try {
            String candidateId = "test_" + System.currentTimeMillis();

            ResumeResponse result = resumeAgent.parseAndStore(
                    file.getBytes(),
                    file.getOriginalFilename(),
                    candidateId,
                    modelConfig
            );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("简历解析失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "解析失败: " + e.getMessage()
            ));
        }
    }
}
