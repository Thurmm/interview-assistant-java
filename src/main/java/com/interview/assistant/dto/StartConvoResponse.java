package com.interview.assistant.dto;

import com.interview.assistant.model.Conversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartConvoResponse {
    private String convoId;
    private String welcome;
    private String firstQuestion;
    private Conversation convo;
}
