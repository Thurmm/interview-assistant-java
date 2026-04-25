package com.interview.assistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.model.AppSettings;
import com.interview.assistant.service.DocumentParserService;
import com.interview.assistant.service.LlmService;
import com.interview.assistant.service.SettingsService;
import com.interview.assistant.service.VectorStoreException;
import com.interview.assistant.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/vector")
@RequiredArgsConstructor
public class VectorStoreController {

    private final VectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper;

    @Autowired
    private DocumentParserService documentParserService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private SettingsService settingsService;

    // ========== Store reference answer ==========

    @PostMapping("/reference")
    public Map<String, Object> storeReference(@RequestBody Map<String, Object> body) {
        try {
            String question = (String) body.get("question");
            String answer = (String) body.get("answer");
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) body.get("tags");
            if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
                return Map.of("success", false, "error", "question and answer cannot be empty");
            }
            vectorStoreService.storeReferenceAnswer(question, answer, tags);
            return Map.of("success", true, "message", "Reference answer stored");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Search reference answers ==========

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String query, @RequestParam(defaultValue = "3") int topK) {
        try {
            String result = vectorStoreService.retrieveReferenceAnswer(query, topK);
            if (result == null || result.isBlank()) {
                // 空结果：知识库为空 或 embedding 服务不可用
                boolean embeddingAvailable = vectorStoreService.isEmbeddingAvailable();
                if (!embeddingAvailable) {
                    return Map.of(
                            "success", false,
                            "error", "Embedding 服务不可用（账户余额不足或 API 未配置），请联系管理员处理。",
                            "errorCode", "EMBEDDING_UNAVAILABLE"
                    );
                }
                return Map.of("success", true, "query", query, "results", "", "empty", true);
            }
            return Map.of("success", true, "query", query, "results", result);
        } catch (VectorStoreException e) {
            return Map.of("success", false, "error", e.getMessage(), "errorCode", e.getErrorCode());
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Get all reference answers ==========

    @GetMapping("/reference")
    public Map<String, Object> getAllReferences() {
        try {
            List<Map<String, Object>> items = vectorStoreService.getAllReferenceAnswers();
            return Map.of("success", true, "items", items, "count", items.size());
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Vector store statistics ==========

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        int totalDocs = vectorStoreService.getDocCount();
        int refCount = vectorStoreService.getReferenceCount();
        int resumeCount = vectorStoreService.getResumeCount();
        return Map.of(
                "success", true,
                "totalDocs", totalDocs,
                "referenceCount", refCount,
                "resumeCount", resumeCount,
                "mode", "memory mode"
        );
    }

    // ========== Batch store reference answers ==========

    @PostMapping("/reference/batch")
    public Map<String, Object> storeBatch(@RequestBody List<Map<String, Object>> items) {
        try {
            int count = 0;
            for (Map<String, Object> item : items) {
                String q = (String) item.get("question");
                String a = (String) item.get("answer");
                @SuppressWarnings("unchecked")
                List<String> t = (List<String>) item.get("tags");
                if (q != null && a != null) {
                    vectorStoreService.storeReferenceAnswer(q, a, t);
                    count++;
                }
            }
            return Map.of("success", true, "stored", count, "total", items.size());
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Single reference delete/update ==========

    @DeleteMapping("/reference")
    public Map<String, Object> deleteReference(@RequestParam("question") String question) {
        try {
            boolean ok = vectorStoreService.deleteReferenceAnswer(question);
            return ok
                    ? Map.of("success", true, "message", "已删除")
                    : Map.of("success", false, "error", "未找到该问题");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PutMapping("/reference")
    public Map<String, Object> updateReference(@RequestBody Map<String, Object> body) {
        try {
            String oldQuestion = (String) body.get("oldQuestion");
            String newQuestion = (String) body.get("newQuestion");
            String newAnswer = (String) body.get("newAnswer");
            @SuppressWarnings("unchecked")
            List<String> newTags = body.get("newTags") != null ? (List<String>) body.get("newTags") : List.of();
            if (oldQuestion == null || newAnswer == null) {
                return Map.of("success", false, "error", "参数不完整");
            }
            // If newQuestion is empty, keep old question text
            if (newQuestion == null || newQuestion.trim().isEmpty()) {
                newQuestion = oldQuestion;
            }
            vectorStoreService.updateReferenceAnswer(oldQuestion, newQuestion, newAnswer, newTags);
            return Map.of("success", true, "message", "已更新");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Clear reference knowledge base ==========

    @DeleteMapping("/reference/clear")
    public Map<String, Object> clearReference() {
        try {
            vectorStoreService.clearReferenceAnswers();
            return Map.of("success", true, "message", "Reference knowledge base cleared");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Document import: parse -> LLM split Q&A -> batch store ==========

    @PostMapping("/document/import")
    public Map<String, Object> importDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "overlap", defaultValue = "0") int overlapChars
    ) {
        try {
            if (file == null || file.isEmpty()) {
                return Map.of("success", false, "error", "File cannot be empty");
            }
            String filename = file.getOriginalFilename();
            String text = documentParserService.parse(file);
            if (text == null || text.isBlank()) {
                return Map.of("success", false, "error", "Cannot extract text from document");
            }
            // 限制文本长度，避免超出 LLM 上下文窗口
            int maxChars = 30000;
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars);
                log.info("[VectorStore] Document text truncated to {} chars", maxChars);
            }
            log.info("[VectorStore] Document import: {}, text length={}", filename, text.length());

            String prompt = "You are a professional interview question bank organizer. "
                    + "Extract all Q&A pairs from the following document content and return as JSON array.\n\n"
                    + "Document content:\n" + text + "\n\n"
                    + "Requirements:\n"
                    + "1. Each element must contain 'question' and 'answer' fields.\n"
                    + "2. If the original text is 'Q: xxx A: yyy', extract directly.\n"
                    + "3. For paragraphs without clear Q&A format, generate reasonable Q&A by understanding the content.\n"
                    + "4. Extract multiple choice, short answer, and coding questions.\n"
                    + "5. Only extract content that explicitly exists in the document, do not fabricate.\n"
                    + "6. Keep each question concise within 50 characters; answer within 200 characters.\n"
                    + "7. Return pure JSON array without any markdown markers.\n\n"
                    + "Example format:\n"
                    + "[{\"question\":\"HashMap vs Hashtable?\",\"answer\":\"HashMap is not thread-safe...\",\"tags\":[\"Java\",\"Collection\"]}]";

            AppSettings.ModelConfig modelConfig = settingsService.getSettings().getModelConfig();
            LlmService.LlmResult llmResult = llmService.callLlmWithRaw(
                    List.of(
                            Map.of("role", "system", "content", "You are a professional interview question bank organizer. Strictly extract Q&A pairs from the document. Return pure JSON array."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    modelConfig
            );

            if (llmResult == null || !llmResult.isSuccess()) {
                String errDetail = llmResult != null && llmResult.getErrorMessage() != null
                        ? llmResult.getErrorMessage()
                        : (llmResult != null ? "LLM returned empty response" : "LLM call failed");
                // truncate long error messages for display
                if (errDetail.length() > 200) errDetail = errDetail.substring(0, 200) + "...";
                return Map.of("success", false, "error", "LLM调用失败: " + errDetail);
            }
            String llmRaw = llmResult.getContent();

            String jsonStr = extractJsonArray(llmRaw);
            JsonNode root = objectMapper.readTree(jsonStr);
            if (!root.isArray()) {
                return Map.of("success", false, "error", "LLM returned format error",
                        "rawHint", llmRaw.substring(0, Math.min(200, llmRaw.length())));
            }

            List<Map<String, Object>> qas = new ArrayList<>();
            int stored = 0;
            for (JsonNode node : root) {
                String q = node.has("question") ? node.get("question").asText("").trim() : "";
                String a = node.has("answer") ? node.get("answer").asText("").trim() : "";
                if (q.isEmpty() || a.isEmpty()) continue;
                List<String> tags = new ArrayList<>();
                if (node.has("tags") && node.get("tags").isArray()) {
                    node.get("tags").forEach(t -> tags.add(t.asText()));
                }
                vectorStoreService.storeReferenceAnswer(q, a, tags);
                qas.add(Map.of("question", q, "answer", a));
                stored++;
            }

            log.info("[VectorStore] Document {} split-imported {} Q&A pairs", filename, stored);
            return Map.of(
                    "success", true,
                    "message", "Document parsed successfully",
                    "filename", filename,
                    "textLength", text.length(),
                    "extracted", root.size(),
                    "stored", stored,
                    "qas", qas
            );

        } catch (Exception e) {
            log.error("[VectorStore] Document import failed", e);
            return Map.of("success", false, "error", "Import failed: " + e.getMessage());
        }
    }

    // ========== SSE streaming document import ==========

    @PostMapping(value = "/document/import/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody importDocumentStream(@RequestParam("file") MultipartFile file) {
        return outputStream -> {
            try (outputStream) {
                var writer = new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8);

                if (file == null || file.isEmpty()) {
                    writer.write("event: error\ndata: {\"error\":\"File cannot be empty\"}\n\n");
                    writer.flush();
                    return;
                }

                String filename = file.getOriginalFilename();
                String text;
                try {
                    text = documentParserService.parse(file);
                } catch (Exception e) {
                    writer.write("event: error\ndata: {\"error\":\"Document parsing failed: " + e.getMessage().replace("\"", "\\\"") + "\"}\n\n");
                    writer.flush();
                    return;
                }

                if (text == null || text.isBlank()) {
                    writer.write("event: error\ndata: {\"error\":\"Cannot extract text from document\"}\n\n");
                    writer.flush();
                    return;
                }

                log.info("[VectorStore-SSE] Starting streaming import: {}, text length={}", filename, text.length());

                String prompt = "You are a professional interview question bank organizer. "
                        + "Extract all Q&A pairs from the following document content and return as JSON array.\n\n"
                        + "Document content:\n" + text + "\n\n"
                        + "Requirements:\n"
                        + "1. Each element must contain 'question' and 'answer' fields.\n"
                        + "2. If the original text is 'Q: xxx A: yyy', extract directly.\n"
                        + "3. For paragraphs without clear Q&A format, generate reasonable Q&A.\n"
                        + "4. Only extract content that explicitly exists, do not fabricate.\n"
                        + "5. Keep each question within 50 characters; answer within 200 characters.\n"
                        + "6. Return pure JSON array without markdown.\n\n"
                        + "Example: [{\"question\":\"...\",\"answer\":\"...\",\"tags\":[\"Java\"]}]";

                AppSettings.ModelConfig modelConfig = settingsService.getSettings().getModelConfig();
                String llmRaw = llmService.callLlm(
                        List.of(
                                Map.of("role", "system", "content", "You are a professional interview question bank organizer. Strictly extract Q&A from the document. Return pure JSON array."),
                                Map.of("role", "user", "content", prompt)
                        ),
                        modelConfig
                );

                if (llmRaw == null || llmRaw.isBlank()) {
                    writer.write("event: error\ndata: {\"error\":\"LLM analysis failed\"}\n\n");
                    writer.flush();
                    return;
                }

                String jsonStr = extractJsonArray(llmRaw);
                JsonNode root;
                try {
                    root = objectMapper.readTree(jsonStr);
                } catch (Exception e) {
                    writer.write("event: error\ndata: {\"error\":\"LLM returned format error\",\"rawHint\":\"" + llmRaw.substring(0, Math.min(100, llmRaw.length())).replace("\"", "\\\"") + "\"}\n\n");
                    writer.flush();
                    return;
                }

                if (!root.isArray()) {
                    writer.write("event: error\ndata: {\"error\":\"LLM did not return a JSON array\"}\n\n");
                    writer.flush();
                    return;
                }

                // Step 1: Send all extracted Q&As with initial pending status
                writer.write("event: preview\ndata: " + jsonStr + "\n\n");
                writer.flush();

                // Step 2: Store each one and send status events
                int stored = 0;
                int idx = 0;
                for (JsonNode node : root) {
                    String q = node.has("question") ? node.get("question").asText("").trim() : "";
                    String a = node.has("answer") ? node.get("answer").asText("").trim() : "";
                    if (q.isEmpty() || a.isEmpty()) continue;

                    List<String> tags = new ArrayList<>();
                    if (node.has("tags") && node.get("tags").isArray()) {
                        node.get("tags").forEach(t -> tags.add(t.asText()));
                    }

                    try {
                        vectorStoreService.storeReferenceAnswer(q, a, tags);
                        stored++;
                        String shortQ = q.length() > 30 ? q.substring(0, 30) : q;
                        writer.write("event: item_stored\ndata: {\"idx\":" + idx + ",\"status\":\"stored\",\"question\":\"" + shortQ.replace("\"", "\\\"") + "\"}\n\n");
                    } catch (Exception ex) {
                        log.warn("[VectorStore-SSE] Item {} store failed: {}", idx, ex.getMessage());
                        String shortQ = q.length() > 30 ? q.substring(0, 30) : q;
                        writer.write("event: item_stored\ndata: {\"idx\":" + idx + ",\"status\":\"error\",\"question\":\"" + shortQ.replace("\"", "\\\"") + "\",\"error\":\"" + ex.getMessage().replace("\"", "\\\"") + "\"}\n\n");
                    }
                    writer.flush();
                    idx++;
                }

                writer.write("event: done\ndata: {\"filename\":\"" + filename + "\",\"textLength\":" + text.length() + ",\"extracted\":" + root.size() + ",\"stored\":" + stored + "}\n\n");
                writer.flush();
                log.info("[VectorStore-SSE] Streaming import complete: extracted {} stored {}", root.size(), stored);

            } catch (Exception e) {
                log.error("[VectorStore-SSE] Streaming import exception", e);
            }
        };
    }

    /**
     * Extract JSON array from LLM response.
     * Handles truncated JSON by truncating to the last complete object boundary.
     */
    private String extractJsonArray(String raw) {
        String s = raw.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return s;
        String candidate = s.substring(start, end + 1);
        // Try parsing directly first
        try {
            objectMapper.readTree(candidate);
            return candidate;
        } catch (Exception ok) {
            // Truncated — walk backwards from the end to find last complete object
        }
        int braceCount = 0;
        int inString = 0;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c == '\\' && inString > 0) {
                i--; // skip escaped char
                continue;
            }
            if (c == '"') {
                inString = inString > 0 ? 0 : 1;
                continue;
            }
            if (inString > 0) continue;
            if (c == '}') braceCount++;
            if (c == '{') {
                braceCount--;
                if (braceCount == 0) {
                    // Found last complete object — include its closing brace and everything before
                    String valid = candidate.substring(0, i);
                    // Ensure it ends with ]
                    int lastBrace = valid.lastIndexOf('}');
                    if (lastBrace >= 0) {
                        int afterBrace = lastBrace + 1;
                        // skip whitespace
                        while (afterBrace < valid.length() && (valid.charAt(afterBrace) == ' ' || valid.charAt(afterBrace) == '\n' || valid.charAt(afterBrace) == '\r' || valid.charAt(afterBrace) == ',')) {
                            afterBrace++;
                        }
                        if (afterBrace < valid.length() && valid.charAt(afterBrace) == ']') {
                            return valid.substring(0, afterBrace + 1);
                        }
                        return valid.trim() + "]";
                    }
                }
            }
        }
        // Fallback: return what we have, let parser fail with useful error
        return candidate;
    }
}
