package com.interview.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Conversation {
    private String id;
    private String createdAt;
    private String updatedAt;
    private Map<String, Object> settings;
    private List<Message> messages;
    private Integer currentQuestionIndex;
    private String status;  // in_progress, completed, stopped

    /** 候选人画像（由 ResumeAgent 生成） */
    private CandidateProfile candidateProfile;

    /** 当前面试阶段（Opening → Technical → Behavioral → DeepDive → WrapUp） */
    private String interviewPhase;
}
