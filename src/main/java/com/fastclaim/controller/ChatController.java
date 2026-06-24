package com.fastclaim.controller;

import com.fastclaim.dto.ChatRequest;
import com.fastclaim.dto.ChatResponse;
import com.fastclaim.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发送消息 — 首次调用不传 sessionId，服务端创建新会话。
     */
    @PostMapping
    @PreAuthorize("hasAuthority('chat:use')")
    public ResponseEntity<ChatResponse> sendMessage(
            @Valid @RequestBody ChatRequest request,
            @RequestParam(required = false) String sessionId) {

        log.debug("收到消息 — sessionId: {}, message: {}",
                sessionId,
                request.message().substring(0, Math.min(100, request.message().length())));

        ChatResponse response = chatService.processChat(request.message(), sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * 清除指定会话。
     */
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('chat:use')")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        log.info("清除会话 — sessionId: {}", sessionId);
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
