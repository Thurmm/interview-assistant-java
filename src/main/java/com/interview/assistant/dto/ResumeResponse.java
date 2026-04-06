package com.interview.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简历解析结果 + 候选人画像
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeResponse {

    /** 原始文本（解析出的简历全文） */
    private String rawText;

    /** 候选人姓名 */
    private String name;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 最高学历 */
    private String education;

    /** 工作年限 */
    private String workExperience;

    /** 核心技术栈列表 */
    private java.util.List<String> techStack;

    /** 工作经历摘要 */
    private java.util.List<String> workHistory;

    /** 项目经历摘要 */
    private java.util.List<String> projectHistory;

    /** 候选人整体画像描述（供面试官使用） */
    private String profileSummary;

    /** 是否解析成功 */
    private boolean success;

    /** 错误信息（解析失败时填充） */
    private String errorMessage;
}
