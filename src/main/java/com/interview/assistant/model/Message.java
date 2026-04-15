package com.interview.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {
    private String role;       // "interviewer" or "user"
    private String content;
    private String timestamp;
    private Boolean isQuestion;
    private Integer score;
    private String feedback;
    @JsonProperty("model_answer")
    private String modelAnswer;
}
