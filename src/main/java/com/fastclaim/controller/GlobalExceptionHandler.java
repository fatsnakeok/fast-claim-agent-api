package com.fastclaim.controller;

import com.fastclaim.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 全局异常处理 — 将 ChatService 抛出的业务异常统一映射为 HTTP 标准状态码。
 * 避免 Controller 中重复写 try-catch，同时保证错误响应体格式一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 输入护栏拒绝 — 422 Unprocessable Entity。
     * 422 而非 400，因为请求语法正确但语义不被接受（恶意注入/无关话题）。
     */
    @ExceptionHandler(ChatService.InputRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleInputRejected(
            ChatService.InputRejectedException e) {
        log.warn("输入护栏拒绝 — {}", e.getMessage());
        return ResponseEntity.status(422).body(Map.of(
                "error", "INPUT_REJECTED",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * 会话不存在 — 404 Not Found。
     * sessionId 无效或已被手动清除。
     */
    @ExceptionHandler(ChatService.SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSessionNotFound(
            ChatService.SessionNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of(
                "error", "SESSION_NOT_FOUND",
                "message", e.getMessage()
        ));
    }

    /**
     * 会话已过期 — 410 Gone。
     * 用 410 而非 404，语义更精确：资源曾经存在但已不可用，客户端应新建会话。
     */
    @ExceptionHandler(ChatService.SessionExpiredException.class)
    public ResponseEntity<Map<String, String>> handleSessionExpired(
            ChatService.SessionExpiredException e) {
        return ResponseEntity.status(410).body(Map.of(
                "error", "SESSION_EXPIRED",
                "message", e.getMessage()
        ));
    }
}
