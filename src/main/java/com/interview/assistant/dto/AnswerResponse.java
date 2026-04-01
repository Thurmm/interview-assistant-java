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
    }
}
