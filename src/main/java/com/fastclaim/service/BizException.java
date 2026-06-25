package com.fastclaim.service;

/**
 * 业务异常，携带错误码。
 * 全局异常处理器按错误码场景映射 HTTP 状态码。
 */
public class BizException extends RuntimeException {
    private final String errorCode;

    public BizException(String message) {
        this("BIZ_ERROR", message);
    }

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
