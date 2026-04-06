package com.interview.assistant.dto;

import com.interview.assistant.model.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerResponse {
    private EvaluationResult evaluation;
    private String nextQuestion;
    private Boolean isFinished;
    private List<Message> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationResult {
        private Integer score;
        private String feedback;
        private String modelAnswer;
        /** 分维度评分（V2 新增） */
        private DimensionScores dimensionScores;
    }

    /** 分维度评分 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScores {
        private Integer technicalDepth;    // 技术深度 0-10
        private Integer expressionClarity; // 表达清晰度 0-10
        private Integer logicCoherence;    // 逻辑连贯性 0-10
        private Integer experienceRelevance; // 经验相关性 0-10
    }
}
