package com.interview.assistant.service;

import com.interview.assistant.model.Conversation;
import com.interview.assistant.model.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ConversationService conversationService;

    public String generateReport(String convoId) {
        Conversation convo = conversationService.getConversation(convoId)
            .orElseThrow(() -> new IllegalArgumentException("面试记录不存在"));

        List<Message> userMessages = convo.getMessages().stream()
            .filter(m -> "user".equals(m.getRole()))
            .collect(Collectors.toList());

        if (userMessages.isEmpty()) {
            throw new IllegalArgumentException("暂无回答数据，无法生成报告");
        }

        List<Integer> scores = userMessages.stream()
            .filter(m -> m.getScore() != null)
            .map(Message::getScore)
            .collect(Collectors.toList());

        double avgScore = scores.isEmpty() ? 0 : scores.stream().mapToInt(Integer::intValue).average().orElse(0);

        List<Message> lowScoreMsgs = userMessages.stream()
            .filter(m -> m.getScore() != null && m.getScore() < 4)
            .collect(Collectors.toList());
        List<Message> midScoreMsgs = userMessages.stream()
            .filter(m -> m.getScore() != null && m.getScore() >= 4 && m.getScore() < 7)
            .collect(Collectors.toList());
        List<Message> highScoreMsgs = userMessages.stream()
            .filter(m -> m.getScore() != null && m.getScore() >= 7)
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();

        String position = getSetting(convo, "position", "面试");
        String company = getSetting(convo, "company", "未知");
        String createdAt = convo.getCreatedAt() != null ? convo.getCreatedAt() : "-";
        String statusStr = "completed".equals(convo.getStatus()) ? "已完成"
            : "stopped".equals(convo.getStatus()) ? "已停止" : "进行中";

        sb.append("# ").append(position).append(" - 面试总结报告\n\n");
        sb.append("**公司**: ").append(company).append("\n");
        sb.append("**职位**: ").append(position).append("\n");
        sb.append("**面试时间**: ").append(createdAt).append("\n");
        sb.append("**面试状态**: ").append(statusStr).append("\n\n");
        sb.append("---\n\n");
        sb.append("## 📊 面试概况\n\n");
        sb.append("- 总问题数: **").append(userMessages.size()).append("** 道\n");
        if (!scores.isEmpty()) {
            sb.append("- 最高分: **").append(Collections.max(scores)).append("** /10\n");
            sb.append("- 最低分: **").append(Collections.min(scores)).append("** /10\n");
        }
        sb.append("- 平均分: **").append(String.format("%.1f", avgScore)).append("** /10\n\n");

        sb.append("---\n\n");
        sb.append("## 📝 问答详情\n\n");

        // Build Q&A pairs
        List<QaPair> qaPairs = new ArrayList<>();
        for (Message msg : convo.getMessages()) {
            if ("interviewer".equals(msg.getRole()) && Boolean.TRUE.equals(msg.getIsQuestion())) {
                qaPairs.add(new QaPair(msg.getContent(), null, null, null, null));
            } else if ("user".equals(msg.getRole()) && !qaPairs.isEmpty()) {
                QaPair last = qaPairs.get(qaPairs.size() - 1);
                last.answer = msg.getContent();
                last.score = msg.getScore();
                last.feedback = msg.getFeedback();
                last.modelAnswer = msg.getModelAnswer();
            }
        }

        int qNum = 0;
        for (QaPair qa : qaPairs) {
            qNum++;
            Integer score = qa.score;
            String scoreStr = score != null ? score + "/10" : "未评分";
            String emoji = score != null ? (score >= 7 ? "🟢" : (score >= 4 ? "🟡" : "🔴")) : "⚪";

            sb.append("### ").append(emoji).append(" 第").append(qNum).append("题 (得分: ").append(scoreStr).append(")\n\n");
            sb.append("**面试官提问**: ").append(qa.question).append("\n\n");
            sb.append("**你的回答**: ").append(qa.answer != null ? qa.answer : "未回答").append("\n\n");
            if (qa.feedback != null && !qa.feedback.isBlank()) {
                sb.append("**💡 点评**: ").append(qa.feedback).append("\n\n");
            }
            if (qa.modelAnswer != null && !qa.modelAnswer.isBlank()) {
                sb.append("**✨ 参考回答**: ").append(qa.modelAnswer).append("\n\n");
            }
            sb.append("---\n\n");
        }

        if (!lowScoreMsgs.isEmpty()) {
            sb.append("## 🔴 需要重点强化\n\n");
            for (QaPair qa : qaPairs) {
                if (qa.score != null && qa.score < 4) {
                    sb.append("- **").append(truncate(qa.question, 50)).append("...**\n");
                    if (qa.modelAnswer != null) {
                        sb.append("  - 参考: ").append(truncate(qa.modelAnswer, 100)).append("...\n");
                    }
                    sb.append("\n");
                }
            }
        }

        if (!midScoreMsgs.isEmpty()) {
            sb.append("## 🟡 可进一步提升\n\n");
            for (QaPair qa : qaPairs) {
                if (qa.score != null && qa.score >= 4 && qa.score < 7) {
                    sb.append("- **").append(truncate(qa.question, 50)).append("...**\n");
                    if (qa.modelAnswer != null) {
                        sb.append("  - 参考: ").append(truncate(qa.modelAnswer, 100)).append("...\n");
                    }
                    sb.append("\n");
                }
            }
        }

        if (!highScoreMsgs.isEmpty()) {
            sb.append("## 🟢 掌握良好\n\n");
            for (QaPair qa : qaPairs) {
                if (qa.score != null && qa.score >= 7) {
                    sb.append("- ").append(truncate(qa.question, 60)).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("---\n\n");
        sb.append("## 💪 总体建议\n\n");

        String advice;
        if (avgScore >= 7) {
            advice = "本次面试表现良好，继续保持！建议针对中等分数的问题进行针对性练习。";
        } else if (avgScore >= 4) {
            advice = "面试表现中等，建议系统复习相关知识点，重点突破弱项。多做模拟练习。";
        } else {
            advice = "面试基础需要加强，建议先系统学习岗位相关知识，多做练习后再尝试面试。";
        }
        sb.append(advice).append("\n\n");
        sb.append("*报告生成时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("*\n");

        return sb.toString();
    }

    private String getSetting(Conversation convo, String key, String defaultValue) {
        Object val = convo.getSettings().get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private static class QaPair {
        String question;
        String answer;
        Integer score;
        String feedback;
        String modelAnswer;

        QaPair(String question, String answer, Integer score, String feedback, String modelAnswer) {
            this.question = question;
            this.answer = answer;
            this.score = score;
            this.feedback = feedback;
            this.modelAnswer = modelAnswer;
        }
    }
}
