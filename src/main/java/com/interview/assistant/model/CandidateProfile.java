package com.interview.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 候选人画像
 *
 * 由 ResumeAgent 解析简历后生成，存储在 Conversation 中，
 * 供 InterviewerAgent 出题时个性化使用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CandidateProfile {

    /** 候选人 ID（对应向量数据库中的文档 ID） */
    private String candidateId;

    /** 姓名 */
    private String name;

    /** 邮箱 */
    private String email;

    /** 手机 */
    private String phone;

    /** 最高学历 */
    private String education;

    /** 工作年限 */
    private String workExperience;

    /** 核心技术栈 */
    private List<String> techStack;

    /** 工作经历列表 */
    private List<String> workHistory;

    /** 项目经历列表 */
    private List<String> projectHistory;

    /** 画像摘要（供面试官使用的一段话描述） */
    private String profileSummary;

    /** 原始简历文本 */
    private String rawText;

    /** 是否已上传简历 */
    private boolean resumeUploaded;
}
