package com.interview.assistant.service;

import com.interview.assistant.model.SavedResume;
import com.interview.assistant.util.JsonFileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final String FILENAME = "saved_resumes.json";

    private final JsonFileUtil jsonFileUtil;
    private List<SavedResume> cache = new ArrayList<>();

    @PostConstruct
    public void init() {
        cache = jsonFileUtil.readJsonList(FILENAME, SavedResume.class, new ArrayList<>());
        log.info("简历服务初始化，加载 {} 条已保存简历", cache.size());
    }

    private void save() {
        jsonFileUtil.writeJson(FILENAME, cache);
    }

    /** 获取全部 */
    public List<SavedResume> list() {
        return new ArrayList<>(cache);
    }

    /** 根据 ID 获取 */
    public Optional<SavedResume> get(String id) {
        return cache.stream().filter(r -> r.getId().equals(id)).findFirst();
    }

    /** 保存（从简历解析结果） */
    public SavedResume saveFromParse(String name, String email, String phone, String education,
                                     List<String> techStack, List<String> workHistory,
                                     List<Map<String, String>> projectHistory, String profileSummary,
                                     String rawText, String remark) {
        SavedResume resume = SavedResume.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .name(name)
                .email(email)
                .phone(phone)
                .education(education)
                .techStack(techStack)
                .workHistory(workHistory)
                .projectHistory(projectHistory)
                .profileSummary(profileSummary)
                .rawText(rawText)
                .savedAt(System.currentTimeMillis())
                .remark(remark)
                .build();
        cache.add(0, resume); // 最新在前
        save();
        return resume;
    }

    /** 更新 */
    public Optional<SavedResume> update(String id, SavedResume updated) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).getId().equals(id)) {
                updated.setId(id);
                updated.setSavedAt(cache.get(i).getSavedAt()); // 保留原保存时间
                cache.set(i, updated);
                save();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    /** 删除 */
    public boolean delete(String id) {
        boolean removed = cache.removeIf(r -> r.getId().equals(id));
        if (removed) save();
        return removed;
    }
}
