package com.fastclaim.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端会话 — 维护对话历史和生命周期。
 * 线程安全由 ChatService 的 ConcurrentHashMap 保证，本类不自行加锁。
 */
public class ChatSession {

    private final String sessionId;
    private final String userId;
    private final List<ChatMessage> messages;
    private final LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private boolean active;

    public ChatSession(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.messages = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.lastAccessedAt = LocalDateTime.now();
        this.active = true;
    }

    /**
     * 添加一轮对话，超过历史上限自动截断最早的消息对。
     * @param maxRounds 最大保留轮数
     */
    public void addMessagePair(String userMsg, String assistantMsg, int maxRounds) {
        messages.add(new ChatMessage("user", userMsg, LocalDateTime.now()));
        messages.add(new ChatMessage("assistant", assistantMsg, LocalDateTime.now()));
        // 超过上限：一轮 = 2 条消息，截断最早一轮
        int maxMessages = maxRounds * 2;
        while (messages.size() > maxMessages) {
            messages.remove(0);
            messages.remove(0);
        }
    }

    public boolean isExpired(int ttlMinutes) {
        return lastAccessedAt.plusMinutes(ttlMinutes).isBefore(LocalDateTime.now());
    }

    /**
     * 格式化最近 N 轮对话为 LLM 可读的上下文字符串。
     */
    public String getConversationContext(int maxRounds) {
        // 取最近 maxRounds 轮
        int maxMessages = maxRounds * 2;
        int start = Math.max(0, messages.size() - maxMessages);
        List<ChatMessage> recent = messages.subList(start, messages.size());

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : recent) {
            sb.append(msg.role().equals("user") ? "用户：" : "助手：");
            sb.append(msg.content());
            sb.append("\n");
        }
        return sb.toString();
    }

    public void touch() {
        this.lastAccessedAt = LocalDateTime.now();
    }

    // -- getters --

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
