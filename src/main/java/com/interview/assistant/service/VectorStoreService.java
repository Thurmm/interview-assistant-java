package com.interview.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量存储服务（生产可用版本）
 *
 * 使用真实 embedding 向量（OpenAI/MiniMax）替代 TF-IDF，
 * 支持语义相似度检索，而非关键词匹配。
 *
 * - 存储：文本 + 向量 + 元数据
 * - 检索：余弦相似度 top-K
 * - 持久化：JSON 文件（含向量数据）
 *
 * 生产环境可替换为 Qdrant/PGVector，只需修改存储层。
 */
@Slf4j
@Service
public class VectorStoreService {

    /**
     * 内存向量存储的文档对象（含 embedding 向量）
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    private static class StoredDoc {
        private String id;
        private String content;
        private Map<String, Object> metadata;
        /** 预计算的文本向量（1536 维 / MiniMax embedding-2） */
        private float[] embedding;
    }

    // ============ 内存存储 ============
    private final ConcurrentHashMap<String, StoredDoc> docStore = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BgeEmbeddingService embeddingService;
    private static final String PERSIST_FILE = "data/vector_refs.json";

    public VectorStoreService(BgeEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        log.info("VectorStoreService 初始化（语义向量模式）");
        loadFromDisk();
    }

    // ============ 持久化（JSON，含向量数据）============

    private synchronized void loadFromDisk() {
        try {
            java.io.File f = new java.io.File(PERSIST_FILE);
            if (f.exists()) {
                List<StoredDoc> list = objectMapper.readValue(f,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, StoredDoc.class));

                // 检测真实 embedding 维度（可能因欠费失败）
                int expectedDim;
                try {
                    expectedDim = embeddingService.getDimension();
                } catch (Exception e) {
                    String em = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (em.contains("connection") || em.contains("refused") || em.contains("unable to connect") || em.contains("connect")) {
                        log.warn("[VectorStore] 无法连接 BGE Embedding 服务（localhost:8001），跳过向量重新生成: {}", e.getMessage());
                    } else {
                        log.warn("[VectorStore] 无法获取 embedding 维度，跳过向量重新生成: {}", e.getMessage());
                    }
                    list.forEach(doc -> docStore.put(doc.getId(), doc));
                    log.info("从磁盘恢复 {} 条参考问答（原有向量）", list.size());
                    return;
                }

                if (expectedDim == 0) {
                    log.warn("[VectorStore] 无法获取 embedding 维度，保留原有向量");
                    expectedDim = -1;
                }

                int reEmbedCount = 0;
                for (StoredDoc doc : list) {
                    // 旧 TF-IDF 数据维度为 0 或不匹配（真实 embedding 应为 1536），需重新生成
                    boolean needsReEmbed = expectedDim > 0
                            && (doc.getEmbedding() == null
                                || doc.getEmbedding().length == 0
                                || doc.getEmbedding().length != expectedDim);

                    if (needsReEmbed) {
                        float[] newEmb = embeddingService.embed(doc.getContent());
                        if (newEmb != null && newEmb.length > 0) {
                            doc.setEmbedding(newEmb);
                            reEmbedCount++;
                            log.info("[VectorStore] 重新向量化: {} → dim={}",
                                    doc.getContent().substring(0, Math.min(30, doc.getContent().length())),
                                    newEmb.length);
                        }
                    }
                    docStore.put(doc.getId(), doc);
                }

                if (reEmbedCount > 0) {
                    log.warn("[VectorStore] 检测到 {} 条旧数据维度不匹配，已重新生成向量", reEmbedCount);
                    saveToDisk(); // 持久化新向量
                }

                log.info("从磁盘恢复 {} 条参考问答（含向量）", list.size());
            }
        } catch (Exception e) {
            log.warn("从磁盘恢复参考问答失败: {}", e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            List<StoredDoc> refs = docStore.values().stream()
                    .filter(d -> "reference_answer".equals(d.getMetadata().get("type")))
                    .collect(Collectors.toList());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    new java.io.File(PERSIST_FILE), refs);
        } catch (Exception e) {
            log.warn("保存参考问答到磁盘失败: {}", e.getMessage());
        }
    }

    // ============ 简历文档存储 ============

    /**
     * 存储简历文档（含向量化）
     */
    public void storeResume(String candidateId, String resumeText, Map<String, Object> metadata) {
        try {
            if (metadata == null) metadata = new HashMap<>();
            metadata.put("type", "resume");
            float[] embedding = embeddingService.embed(resumeText);

            StoredDoc doc = new StoredDoc(candidateId, resumeText, metadata, embedding);
            docStore.put(candidateId, doc);
            log.info("简历已存入向量库: id={}, 文本长度={}, 向量维度={}",
                    candidateId, resumeText.length(), embedding != null ? embedding.length : 0);
        } catch (Exception e) {
            log.error("简历存储失败: {}", candidateId, e);
        }
    }

    /**
     * RAG 检索：根据查询文本找最相似的简历内容（向量语义检索）
     */
    public String retrieveContext(String candidateId, String query, int topK) {
        List<StoredDoc> docs = docStore.values().stream()
                .filter(d -> candidateId == null || candidateId.equals(d.getId()))
                .collect(Collectors.toList());

        if (docs.isEmpty()) {
            return "";
        }

        return retrieveTopDocs(query, docs, topK).stream()
                .map(StoredDoc::getContent)
                .collect(Collectors.joining("\n\n"));
    }

    // ============ 参考答案存储 ============

    /**
     * 存储参考答案（含向量化）
     */
    public void storeReferenceAnswer(String question, String answer, List<String> tags) {
        try {
            String combined = "【问题】\n" + question + "\n\n【参考答案】\n" + answer;
            String docId = "ref_" + System.currentTimeMillis();
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "reference_answer");
            meta.put("tags", String.join(",", tags != null ? tags : List.of()));
            meta.put("question", question); // 用于精准匹配

            float[] embedding = embeddingService.embed(combined);
            StoredDoc doc = new StoredDoc(docId, combined, meta, embedding);
            docStore.put(docId, doc);
            log.info("参考答案已存入向量库: {}, 向量维度={}",
                    question.substring(0, Math.min(30, question.length())),
                    embedding != null ? embedding.length : 0);
            saveToDisk();
        } catch (Exception e) {
            log.error("参考答案存储失败: {}", e.getMessage());
        }
    }

    /**
     * 检索参考答案（向量语义检索）
     */
    public String retrieveReferenceAnswer(String question, int topK) {
        if (docStore.isEmpty()) {
            return "";
        }

        List<StoredDoc> refDocs = docStore.values().stream()
                .filter(d -> "reference_answer".equals(d.getMetadata().get("type")))
                .collect(Collectors.toList());

        if (refDocs.isEmpty()) {
            return "";
        }

        return retrieveTopDocs(question, refDocs, topK).stream()
                .map(StoredDoc::getContent)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 检索与技能相关的面试题目（技术面出题指导）
     *
     * @param skills 技术栈列表
     * @param topK   返回数量
     */
    public String retrieveSkillQuestions(List<String> skills, int topK) {
        if (docStore.isEmpty() || skills == null || skills.isEmpty()) {
            return "";
        }

        List<StoredDoc> refDocs = docStore.values().stream()
                .filter(d -> "reference_answer".equals(d.getMetadata().get("type")))
                .collect(Collectors.toList());

        if (refDocs.isEmpty()) {
            return "";
        }

        String skillQuery = String.join(" ", skills);

        // 向量语义检索
        List<StoredDoc> topDocs = retrieveTopDocs(skillQuery, refDocs, topK * 2);

        // 对技能关键词做二次加权排序
        List<Map.Entry<StoredDoc, Float>> scored = new ArrayList<>();
        for (StoredDoc doc : topDocs) {
            float baseScore = cosineSim(
                    embeddingService.embed(skillQuery),
                    doc.getEmbedding()
            );

            // 技能匹配加分
            int matchCount = 0;
            String contentLower = doc.getContent().toLowerCase();
            for (String skill : skills) {
                if (skill.length() > 2 && contentLower.contains(skill.toLowerCase())) {
                    matchCount++;
                }
            }
            float bonus = (float) matchCount / skills.size() * 0.3f;
            scored.add(new AbstractMap.SimpleEntry<>(doc, Math.min(1.0f, baseScore + bonus)));
        }

        return scored.stream()
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> e.getKey().getContent())
                .collect(Collectors.joining("\n---\n"));
    }

    // ============ 向量检索核心 ============

    /**
     * 根据 query 文本检索 top-K 最相似的文档
     */
    private List<StoredDoc> retrieveTopDocs(String query, List<StoredDoc> docs, int topK)
            throws VectorStoreException {
        float[] queryEmbedding;
        try {
            queryEmbedding = embeddingService.embed(query);
        } catch (Exception e) {
            String em = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            String errorCode;
            String userMsg;
            if (em.contains("connection") || em.contains("refused") || em.contains("unable to connect") || em.contains("connect")) {
                errorCode = "BGE_SERVICE_UNAVAILABLE";
                userMsg = "BGE Embedding 服务未启动，请先运行 embedding_service/start.bat（Linux: bash start.sh）";
            } else if (em.contains("socket") || em.contains("timeout") || em.contains("read")) {
                errorCode = "BGE_NETWORK_ERROR";
                userMsg = "BGE Embedding 服务连接超时，请检查网络后重试。";
            } else {
                errorCode = "BGE_ERROR";
                userMsg = "Embedding 服务暂时不可用（" + (e.getMessage() != null ? e.getMessage() : "未知错误") + "），请稍后重试。";
            }
            throw new VectorStoreException(errorCode, userMsg);
        }

        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new VectorStoreException("EMBEDDING_FAILED",
                    "Embedding 服务返回空结果，请检查 API 配置。");
        }

        return docs.stream()
                .filter(doc -> doc.getEmbedding() != null && doc.getEmbedding().length > 0)
                .map(doc -> new AbstractMap.SimpleEntry<>(
                        doc,
                        cosineSim(queryEmbedding, doc.getEmbedding())
                ))
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(AbstractMap.SimpleEntry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 计算两个向量的余弦相似度
     */
    private static float cosineSim(float[] a, float[] b) {
        return EmbeddingService.cosineSimilarity(a, b);
    }

    // ============ 删除操作 ============

    public void deleteResume(String candidateId) {
        docStore.remove(candidateId);
        log.info("简历已从向量库删除: {}", candidateId);
    }

    public boolean deleteReferenceAnswer(String question) {
        String prefix = "【问题】\n" + question + "\n\n【参考答案】";
        String docIdToDelete = null;
        for (Map.Entry<String, StoredDoc> entry : docStore.entrySet()) {
            StoredDoc doc = entry.getValue();
            if (doc.getContent().startsWith(prefix)) {
                docIdToDelete = entry.getKey();
                break;
            }
        }
        if (docIdToDelete != null) {
            docStore.remove(docIdToDelete);
            saveToDisk();
            log.info("参考答案已删除: {}", question.substring(0, Math.min(30, question.length())));
            return true;
        }
        return false;
    }

    public boolean updateReferenceAnswer(String oldQuestion, String newQuestion, String newAnswer, List<String> newTags) {
        deleteReferenceAnswer(oldQuestion);
        storeReferenceAnswer(newQuestion, newAnswer, newTags);
        return true;
    }

    /**
     * 获取所有已存储的参考答案（供前端列表展示）
     */
    public List<Map<String, Object>> getAllReferenceAnswers() {
        if (docStore.isEmpty()) return List.of();
        return docStore.values().stream()
                .filter(d -> "reference_answer".equals(d.getMetadata().get("type")))
                .map(doc -> {
                    String content = doc.getContent();
                    String question = "";
                    String answer = "";
                    if (content.startsWith("【问题】")) {
                        int idx = content.indexOf("\n\n【参考答案】");
                        if (idx > 0) {
                            question = content.substring(4, idx).trim();
                            int ansIdx = content.indexOf("】", idx + 6);
                            if (ansIdx >= 0) {
                                answer = content.substring(ansIdx + 1).replace("\r", "").trim();
                            }
                        }
                    }
                    String tags = (String) doc.getMetadata().getOrDefault("tags", "");
                    return Map.of(
                            "id", doc.getId(),
                            "question", question,
                            "answer", answer,
                            "tags", tags.isEmpty() ? List.of() : List.of(tags.split(","))
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * 判断向量服务是否可用
     */
    /**
     * 判断 embedding 服务是否可用
     */
    public boolean isAvailable() {
        return embeddingService != null && embeddingService.isAvailable();
    }

    /**
     * 判断 embedding 服务是否可用（不含日志输出）
     */
    public boolean isEmbeddingAvailable() {
        try {
            return embeddingService != null && embeddingService.isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    // ============ 统计方法 ============

    public int getDocCount() {
        return docStore.size();
    }

    public int getReferenceCount() {
        return (int) docStore.values().stream()
                .filter(d -> "reference_answer".equals(d.getMetadata().get("type")))
                .count();
    }

    public int getResumeCount() {
        return (int) docStore.values().stream()
                .filter(d -> "resume".equals(d.getMetadata().get("type")))
                .count();
    }

    public void clearReferenceAnswers() {
        docStore.entrySet().removeIf(e ->
                "reference_answer".equals(e.getValue().getMetadata().get("type")));
        log.info("参考知识库已清空");
        saveToDisk();
    }
}
