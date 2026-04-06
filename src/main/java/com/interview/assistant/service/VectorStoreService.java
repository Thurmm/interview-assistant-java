package com.interview.assistant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量存储服务（开发阶段使用）
 *
 * 功能：
 * 1. 简历文档的向量化存储（内存 HashMap）
 * 2. RAG 检索：TF-IDF 文本相似度匹配
 * 3. 参考答案的存储与检索
 *
 * 生产环境（需要替换为 Qdrant / PGVector）：
 * - 部署 Qdrant：docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant
 * - 替换本服务实现类为 QdrantVectorStoreService 即可，业务接口不变
 */
@Slf4j
@Service
public class VectorStoreService {

    /**
     * 内存向量存储的文档对象
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class StoredDoc {
        private String id;
        private String content;
        private Map<String, Object> metadata;
    }

    // ============ 内存存储 ============
    private final Map<String, StoredDoc> docStore = new ConcurrentHashMap<>();

    public VectorStoreService() {
        log.info("VectorStoreService 初始化（内存模式，仅支持纯文本检索）");
    }

    // ============ 简历文档存储 ============

    /**
     * 存储简历文档
     */
    public void storeResume(String candidateId, String resumeText, Map<String, Object> metadata) {
        try {
            StoredDoc doc = new StoredDoc(candidateId, resumeText, metadata);
            docStore.put(candidateId, doc);
            log.info("简历已存入内存向量库: id={}, 文本长度={}", candidateId, resumeText.length());
        } catch (Exception e) {
            log.error("简历存储失败: {}", candidateId, e);
        }
    }

    /**
     * RAG 检索：根据查询文本找最相似的简历内容
     * 使用关键词重叠度（简单实现）
     */
    public String retrieveContext(String candidateId, String query, int topK) {
        List<StoredDoc> docs = docStore.values().stream()
                .filter(d -> candidateId == null || candidateId.equals(d.getId()))
                .collect(Collectors.toList());

        if (docs.isEmpty()) {
            return "";
        }

        // 纯文本包含匹配 + 关键词重叠评分
        String[] queryWords = query.toLowerCase().split("\\s+");
        return docs.stream()
                .map(doc -> new AbstractMap.SimpleEntry<>(
                        doc,
                        calcKeywordScore(queryWords, doc.getContent().toLowerCase())
                ))
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> e.getKey().getContent())
                .collect(Collectors.joining("\n\n"));
    }

    // ============ 参考答案存储 ============

    /**
     * 存储参考答案
     */
    public void storeReferenceAnswer(String question, String answer, List<String> tags) {
        try {
            String combined = "【问题】\n" + question + "\n\n【参考答案】\n" + answer;
            String docId = "ref_" + System.currentTimeMillis();
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "reference_answer");
            meta.put("tags", String.join(",", tags != null ? tags : List.of()));
            docStore.put(docId, new StoredDoc(docId, combined, meta));
            log.info("参考答案已存入: {}", question.substring(0, Math.min(30, question.length())));
        } catch (Exception e) {
            log.error("参考答案存储失败: {}", e.getMessage());
        }
    }

    /**
     * 检索参考答案
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

        String[] queryWords = question.toLowerCase().split("\\s+");
        return refDocs.stream()
                .map(doc -> new AbstractMap.SimpleEntry<>(
                        doc,
                        calcKeywordScore(queryWords, doc.getContent().toLowerCase())
                ))
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> e.getKey().getContent())
                .collect(Collectors.joining("\n---\n"));
    }

    // ============ 删除操作 ============

    public void deleteResume(String candidateId) {
        docStore.remove(candidateId);
        log.info("简历已从向量库删除: {}", candidateId);
    }

    /**
     * 始终返回 true（内存模式始终可用）
     */
    public boolean isAvailable() {
        return true;
    }

    // ============ 关键词重叠评分（简化版 TF-IDF）============

    /**
     * 计算查询词在文档中的重叠得分（0.0 ~ 1.0）
     */
    private float calcKeywordScore(String[] queryWords, String docText) {
        if (queryWords == null || queryWords.length == 0) return 0f;
        int matchCount = 0;
        for (String word : queryWords) {
            if (word.length() > 1 && docText.contains(word)) {
                matchCount++;
            }
        }
        return (float) matchCount / queryWords.length;
    }
}
