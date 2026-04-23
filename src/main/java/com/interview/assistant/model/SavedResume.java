package com.interview.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 已保存的简历
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavedResume {

    /** 唯一 ID */
    private String id;

    /** 姓名 */
    private String name;

    /** 邮箱 */
    private String email;

    /** 手机 */
    private String phone;

    /** 学历 / 工作年限 */
    private String education;

    /** 核心技术栈 */
    private List<String> techStack;

    /** 工作经历 */
    private List<String> workHistory;

    /** 项目经历（每个元素是项目对象） */
    private List<Map<String, String>> projectHistory;

    /** 画像摘要 */
    private String profileSummary;

    /** 原始简历文本 */
    private String rawText;

    /** 保存时间 */
    private long savedAt;

    /** 备注 / 自定义标签 */
    private String remark;
}
