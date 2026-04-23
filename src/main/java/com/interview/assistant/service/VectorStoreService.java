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
    @lombok.NoArgsConstructor
    private static class StoredDoc {
        private String id;
        private String content;
        private Map<String, Object> metadata;
    }

    // ============ 内存存储 ============
    private final Map<String, StoredDoc> docStore = new ConcurrentHashMap<>();
    private static final String PERSIST_FILE = "data/vector_refs.json";

    public VectorStoreService() {
        log.info("VectorStoreService 初始化（内存模式，仅支持纯文本检索）");
        loadFromDisk();
    }

    private synchronized void loadFromDisk() {
        try {
            java.io.File f = new java.io.File(PERSIST_FILE);
            if (f.exists()) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<StoredDoc> list = mapper.readValue(f, mapper.getTypeFactory().constructCollectionType(List.class, StoredDoc.class));
                list.forEach(doc -> docStore.put(doc.getId(), doc));
                log.info("从磁盘恢复 {} 条参考问答", list.size());
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
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(PERSIST_FILE), refs);
        } catch (Exception e) {
            log.warn("保存参考问答到磁盘失败: {}", e.getMessage());
        }
    }

    // ============ 简历文档存储 ============

    /**
     * 存储简历文档
     */
    public void storeResume(String candidateId, String resumeText, Map<String, Object> metadata) {
        try {
            if (metadata == null) metadata = new HashMap<>();
            metadata.put("type", "resume");
            StoredDoc doc = new StoredDoc(candidateId, resumeText, metadata);
            docStore.put(candidateId, doc);
            log.info("简历已存入内存向量库: id={}, 文本长度={}", candidateId, resumeText.length());
        } catch (Exception e) {
            log.error("简历存储失败: {}", candidateId, e);
        }
    }

    /**
     * RAG 检索：根据查询文本找最相似的简历内容
     * 使用余弦相似度计算
     */
    public String retrieveContext(String candidateId, String query, int topK) {
        List<StoredDoc> docs = docStore.values().stream()
                .filter(d -> candidateId == null || candidateId.equals(d.getId()))
                .collect(Collectors.toList());

        if (docs.isEmpty()) {
            return "";
        }

        // 使用余弦相似度计算
        return docs.stream()
                .map(doc -> new AbstractMap.SimpleEntry<>(
                        doc,
                        calcCosineSimilarity(query, doc.getContent())
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
            saveToDisk();
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

        // 使用余弦相似度计算
        return refDocs.stream()
                .map(doc -> new AbstractMap.SimpleEntry<>(
                        doc,
                        calcCosineSimilarity(question, doc.getContent())
                ))
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> e.getKey().getContent())
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 检索与技能相关的面试题目（用于技术面出题指导）
     * @param skills 技术栈列表，如 ["Java", "Spring", "MySQL"]
     * @param topK 返回数量
     * @return 格式化的题目列表，每条包含问题和参考答案
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

        // 构建技能查询词
        String skillQuery = String.join(" ", skills);

        // 使用余弦相似度计算，但按技能关键词加权
        Map<StoredDoc, Float> scored = new HashMap<>();
        for (StoredDoc doc : refDocs) {
            float baseScore = calcCosineSimilarity(skillQuery, doc.getContent());
            // 检查技能关键词在文档中出现的次数（额外加权）
            int skillMatchCount = 0;
            for (String skill : skills) {
                if (skill.length() > 2 && doc.getContent().toLowerCase().contains(skill.toLowerCase())) {
                    skillMatchCount++;
                }
            }
            float bonus = (float) skillMatchCount / skills.size() * 0.3f; // 最多30%加分
            scored.put(doc, Math.min(1.0f, baseScore + bonus));
        }

        return scored.entrySet().stream()
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
     * 根据问题删除参考答案
     */
    public boolean deleteReferenceAnswer(String question) {
        String prefix = "【问题】\n" + question + "\n\n【参考答案】";
        String docIdToDelete = null;
        for (var entry : docStore.entrySet()) {
            if (prefix.equals(entry.getValue().getContent().substring(0, Math.min(prefix.length(), entry.getValue().getContent().length())))) {
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

    /**
     * 更新参考答案
     */
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
                    // 格式：【问题】xxx\n\n【参考答案】yyy
                    String question = "";
                    String answer = "";
                    if (content.startsWith("【问题】")) {
                        int idx = content.indexOf("\n\n【参考答案】");
                        if (idx > 0) {
                            question = content.substring(4, idx).trim();
                            // Find the 】 after 【参考答案】 to extract answer from the right position
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
                .collect(java.util.stream.Collectors.toList());
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

    /**
     * 计算余弦相似度（基于词频）
     */
    private float calcCosineSimilarity(String query, String docText) {
        Map<String, Integer> queryFreq = calculateTermFrequency(query);
        Map<String, Integer> docFreq = calculateTermFrequency(docText);
        
        // 计算向量点积
        double dotProduct = 0;
        for (String term : queryFreq.keySet()) {
            if (docFreq.containsKey(term)) {
                dotProduct += queryFreq.get(term) * docFreq.get(term);
            }
        }
        
        // 计算向量长度
        double queryNorm = calculateNorm(queryFreq);
        double docNorm = calculateNorm(docFreq);
        
        if (queryNorm == 0 || docNorm == 0) {
            return 0f;
        }
        
        return (float) (dotProduct / (queryNorm * docNorm));
    }

    /**
     * 计算词频
     */
    private Map<String, Integer> calculateTermFrequency(String text) {
        Map<String, Integer> freqMap = new HashMap<>();
        String[] words = text.toLowerCase().split("\\s+");
        
        for (String word : words) {
            word = word.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "");
            if (word.length() > 1) {
                freqMap.put(word, freqMap.getOrDefault(word, 0) + 1);
            }
        }
        
        return freqMap;
    }

    /**
     * 计算向量长度
     */
    private double calculateNorm(Map<String, Integer> freqMap) {
        double sum = 0;
        for (int freq : freqMap.values()) {
            sum += freq * freq;
        }
        return Math.sqrt(sum);
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
