package com.interview.assistant.service;

import com.interview.assistant.config.ApiKeyMaskConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * LLM 调用统一封装
 *
 * 所有 Agent/Controller 调用 LLM 时统一使用此类，
 * 保证：异常标准化日志 / 统一降级策略 / 不重复打印敏感信息
 *
 * 日志规范：
 * - ERROR: LLM 调用彻底失败（重试耗尽）
 * - WARN:  单次失败但有降级结果 / 可恢复错误
 * - INFO:  正常调用关键路径
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmHelper {

    private final LlmService llmService;

    /**
     * 安全调用 LLM，返回清洗后的内容
     *
     * @param messages    对话历史
     * @param modelConfig 模型配置
     * @param fallback    降级文本（调用失败时返回）
     * @param operation   操作描述（用于日志，如 "生成面试问题"、"判断结束"）
     * @return LLM 输出或降级文本
     */
    public String call(String operation, List<Map<String, String>> messages,
                      com.interview.assistant.model.AppSettings.ModelConfig modelConfig,
                      String fallback) {
        try {
            String result = llmService.callLlm(messages, modelConfig);
            if (result != null && !result.isBlank()) {
                log.info("[{}] 成功，输出长度={}", operation, result.length());
                return result;
            } else {
                log.warn("[{}] 返回为空，使用降级结果", operation);
                return fallback;
            }
        } catch (Exception e) {
            log.error("[{}] 调用失败（{}），使用降级结果: {}",
                    operation, e.getClass().getSimpleName(), e.getMessage());
            return fallback;
        }
    }

    /**
     * 安全调用 LLM，返回原始结果（含 success 标志）
     *
     * @param operation  操作描述
     * @param messages   对话历史
     * @param modelConfig 模型配置
     * @return LlmResult（永远不为 null）
     */
    public LlmService.LlmResult callWithResult(String operation,
                                                List<Map<String, String>> messages,
                                                com.interview.assistant.model.AppSettings.ModelConfig modelConfig) {
        try {
            LlmService.LlmResult result = llmService.callLlmWithRaw(messages, modelConfig);
            if (result.isSuccess()) {
                log.info("[{}] 成功，raw 长度={}", operation,
                        result.getRawResponse() != null ? result.getRawResponse().length() : 0);
            } else {
                log.warn("[{}] 返回失败: {}", operation, result.getErrorMessage());
            }
            return result;
        } catch (Exception e) {
            log.error("[{}] 调用异常（{}）: {}", operation, e.getClass().getSimpleName(), e.getMessage());
            return new LlmService.LlmResult(null, null, false,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 测试连接
     */
    public boolean testConnection(com.interview.assistant.model.AppSettings.ModelConfig modelConfig) {
        try {
            boolean ok = llmService.testConnection(modelConfig);
            log.info("[LLM 连接测试] {}", ok ? "成功" : "失败");
            return ok;
        } catch (Exception e) {
            log.error("[LLM 连接测试] 异常: {}", e.getMessage());
            return false;
        }
    }
}
