package com.interview.assistant.service;

/**
 * 知识库检索异常
 *
 * 携带用户可读的错误信息，由 VectorStoreController 转换为 HTTP 响应返回给前端。
 */
public class VectorStoreException extends RuntimeException {

    private final String errorCode;

    public VectorStoreException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
