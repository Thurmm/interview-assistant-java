package com.interview.assistant.controller;

import com.interview.assistant.dto.AnswerResponse;
import com.interview.assistant.dto.StartConvoResponse;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.model.CandidateProfile;
import com.interview.assistant.model.Conversation;
import com.interview.assistant.model.Message;
import com.interview.assistant.service.ConversationService;
import com.interview.assistant.service.LlmService;
import com.interview.assistant.service.ReportService;
import com.interview.assistant.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InterviewController {

    private final ConversationService conversationService;
    private final SettingsService settingsService;
    private final ReportService reportService;
    private final LlmService llmService;

    @GetMapping("/settings")
    public ResponseEntity<AppSettings> getSettings() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, String>> saveSettings(@RequestBody AppSettings settings) {
        settingsService.saveSettings(settings);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "设置已保存"));
    }

    @PostMapping("/model/test")
    public ResponseEntity<Map<String, String>> testModel(@RequestBody Map<String, Object> body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "请求体不能为空"));
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> modelConfigMap = (Map<String, Object>) body.get("model_config");
            if (modelConfigMap == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "缺少model_config"));
            }

            AppSettings.ModelConfig config = mapToModelConfig(modelConfigMap);
            boolean ok = llmService.testConnection(config);
            if (ok) {
                return ResponseEntity.ok(Map.of("status", "ok", "message", "连接成功！"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "连接失败，请检查配置"));
            }
        } catch (Exception e) {
            log.error("模型测试失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> getConversations() {
        return ResponseEntity.ok(conversationService.getAllConversations());
    }

    @GetMapping("/conversation/{id}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String id) {
        return conversationService.getConversation(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/conversation/start")
    public ResponseEntity<?> startConversation() {
        try {
            StartConvoResponse resp = conversationService.startConversation();
            return ResponseEntity.ok(resp);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "need_config", true
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "启动失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /**
     * 带简历启动面试（个性化版本）
     * POST /api/conversation/start-with-resume
     * Body: { candidateProfile: CandidateProfile }
     */
    @PostMapping("/conversation/start-with-resume")
    public ResponseEntity<?> startConversationWithResume(@RequestBody Map<String, Object> body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "请求体不能为空",
                        "code", 400
                ));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> profileMap = (Map<String, Object>) body.get("candidateProfile");

            CandidateProfile candidateProfile = null;
            if (profileMap != null) {
                candidateProfile = mapToCandidateProfile(profileMap);
            }

            StartConvoResponse resp;
            if (candidateProfile != null) {
                resp = conversationService.startConversationWithResume(candidateProfile);
            } else {
                resp = conversationService.startConversation();
            }

            return ResponseEntity.ok(resp);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "need_config", true,
                    "code", 400
            ));
        } catch (Exception e) {
            log.error("启动面试失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "启动失败: " + e.getMessage(),
                    "code", 400
            ));
        }
    }

    @PostMapping("/conversation/{id}/answer")
    public ResponseEntity<?> answer(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            if (id == null || id.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "对话ID不能为空",
                        "code", 400
                ));
            }
            if (body == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "请求体不能为空",
                        "code", 400
                ));
            }
            String answer = body.get("answer");
            if (answer == null || answer.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "回答内容不能为空",
                        "code", 400
                ));
            }
            AnswerResponse resp = conversationService.answer(id, answer);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            log.warn("对话不存在: {}", id);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("处理回答失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "code", 400
            ));
        }
    }

    @PostMapping("/conversation/{id}/stop")
    public ResponseEntity<?> stopConversation(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) body.get("messages");
            String status = (String) body.get("status");
            conversationService.stopConversation(id, messages, status);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/conversation/{id}/delete")
    public ResponseEntity<?> deleteConversation(@PathVariable String id) {
        try {
            conversationService.deleteConversation(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/conversation/{id}/report")
    public ResponseEntity<?> downloadReport(@PathVariable String id) {
        try {
            String report = reportService.generateReport(id);
            Conversation convo = conversationService.getConversation(id).orElseThrow();
            String company = convo.getSettings().get("company") != null ? convo.getSettings().get("company").toString() : "未知";
            String date = convo.getCreatedAt() != null ? convo.getCreatedAt().substring(0, 10) : "";
            String filename = "面试报告_" + company + "_" + date + ".md";

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(report.getBytes(StandardCharsets.UTF_8));

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(baos.toByteArray());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private AppSettings.ModelConfig mapToModelConfig(Map<String, Object> map) {
        return AppSettings.ModelConfig.builder()
            .type((String) map.get("type"))
            .apiKey((String) map.get("api_key"))
            .baseUrl((String) map.get("base_url"))
            .model((String) map.get("model"))
            .build();
    }

    @SuppressWarnings("unchecked")
    private CandidateProfile mapToCandidateProfile(Map<String, Object> map) {
        return CandidateProfile.builder()
            .candidateId((String) map.get("candidateId"))
            .name((String) map.get("name"))
            .email((String) map.get("email"))
            .phone((String) map.get("phone"))
            .education((String) map.get("education"))
            .workExperience((String) map.get("workExperience"))
            .techStack(map.get("techStack") != null ? (java.util.List<String>) map.get("techStack") : null)
            .workHistory(map.get("workHistory") != null ? (java.util.List<String>) map.get("workHistory") : null)
            .projectHistory(map.get("projectHistory") != null ? (java.util.List<String>) map.get("projectHistory") : null)
            .profileSummary((String) map.get("profileSummary"))
            .rawText((String) map.get("rawText"))
            .resumeUploaded(map.get("resumeUploaded") != null && (Boolean) map.get("resumeUploaded"))
            .build();
    }
}
