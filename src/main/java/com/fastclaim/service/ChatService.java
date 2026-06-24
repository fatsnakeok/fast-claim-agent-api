package com.fastclaim.service;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.common.core.validation.ValidationResult;
import com.fastclaim.dto.ChatOutput;
import com.fastclaim.dto.ChatResponse;
import com.fastclaim.dto.ChatSession;
import com.fastclaim.dto.UserInput;
import com.fastclaim.guardrail.InsuranceAssistantMessageGuardRailImpl;
import com.fastclaim.guardrail.InsuranceUserInputGuardRailImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableScheduling
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    private final AgentPlatform agentPlatform;
    private final InsuranceUserInputGuardRailImpl inputGuardRail;
    private final InsuranceAssistantMessageGuardRailImpl outputGuardRail;

    @Value("${chat.session.ttl-minutes:30}")
    private int sessionTtlMinutes;

    @Value("${chat.session.max-history-rounds:20}")
    private int maxHistoryRounds;

    public ChatService(AgentPlatform agentPlatform,
                       InsuranceUserInputGuardRailImpl inputGuardRail,
                       InsuranceAssistantMessageGuardRailImpl outputGuardRail) {
        this.agentPlatform = agentPlatform;
        this.inputGuardRail = inputGuardRail;
        this.outputGuardRail = outputGuardRail;
    }

    /**
     * 处理用户消息的核心流程。
     */
    public ChatResponse processChat(String message, String sessionId) {
        String userId = getCurrentUserId();

        // 1. 获取或创建会话
        boolean isNewSession = false;
        ChatSession session;
        if (sessionId == null || sessionId.isEmpty()) {
            session = createSession(userId);
            sessionId = session.getSessionId();
            isNewSession = true;
            log.info("新建会话 — userId: {}, sessionId: {}", userId, sessionId);
        } else {
            session = sessions.get(sessionId);
            if (session == null) {
                throw new SessionNotFoundException("会话不存在: " + sessionId);
            }
            if (session.isExpired(sessionTtlMinutes)) {
                log.info("会话已过期 — sessionId: {}, 最后活动: {}", sessionId, session.getLastAccessedAt());
                throw new SessionExpiredException("会话已过期，请重新发起对话");
            }
        }

        // 2. 用户输入护栏
        ValidationResult inputResult = inputGuardRail.validate(message, null);
        if (!inputResult.isValid()) {
            String reason = inputResult.getErrors().isEmpty()
                    ? "未知原因"
                    : inputResult.getErrors().get(0).getMessage();
            log.warn("输入护栏拒绝 — sessionId: {}, reason: {}", sessionId, reason);
            throw new InputRejectedException(reason);
        }

        // 3. 构造 UserInput（含对话历史上下文）
        UserInput userInput = new UserInput(message, session.getConversationContext(maxHistoryRounds));

        // 4. 调用 Agent
        // 按返回类型匹配，不是按 Agent 名称匹配。
        ChatOutput output = AgentInvocation.on(agentPlatform)
                .returning(ChatOutput.class)
                .invoke(userInput);

        // 5. 回复护栏 — 仅告警不阻断
        ValidationResult outputResult = outputGuardRail.validate(
                new com.embabel.chat.AssistantMessage("assistant", output.answer()), null);
        if (!outputResult.getErrors().isEmpty()) {
            log.warn("回复护栏告警 — sessionId: {}, errors: {}",
                    sessionId, outputResult.getErrors().size());
        }

        // 6. 保存对话记录
        session.addMessagePair(message, output.answer(), maxHistoryRounds);
        session.touch();

        return new ChatResponse(
                output.answer(),
                sessionId,
                isNewSession,
                LocalDateTime.now()
        );
    }

    /**
     * 清除指定会话。
     */
    public void clearSession(String sessionId) {
        ChatSession removed = sessions.remove(sessionId);
        if (removed == null) {
            throw new SessionNotFoundException("会话不存在: " + sessionId);
        }
        removed.setActive(false);
        log.info("会话已清除 — sessionId: {}", sessionId);
    }

    /**
     * 定时清理过期会话 — 每 5 分钟执行。
     */
    @Scheduled(fixedRateString = "${chat.session.cleanup-interval-ms:300000}")
    public void cleanExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(sessionTtlMinutes));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("定时清理过期会话 — 移除 {} 个，剩余 {}", removed, sessions.size());
        }
    }

    private ChatSession createSession(String userId) {
        String id = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(id, userId);
        sessions.put(id, session);
        return session;
    }

    /**
     * 从 SecurityContext 提取当前用户 ID，未认证时返回 "anonymous"。
     */
    private String getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "anonymous";
    }

    // -- 自定义异常 --

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String msg) {
            super(msg);
        }
    }

    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String msg) {
            super(msg);
        }
    }

    public static class InputRejectedException extends RuntimeException {
        public InputRejectedException(String msg) {
            super(msg);
        }
    }
}
