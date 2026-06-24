package com.fastclaim.dto;

/**
 * ChatbotAgent.answerQuestion() 的输入。
 * 包含用户消息和对话历史上下文，LLM 基于此决定检索策略。
 */
public record UserInput(String message, String conversationContext) {
}
