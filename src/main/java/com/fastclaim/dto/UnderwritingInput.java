package com.fastclaim.dto;

/**
 * UnderwritingAgent 的输入 — 携带 userId 和自然语言消息。
 * userId 供 lookupCustomer @Action 从 Blackboard 读取。
 */
public record UnderwritingInput(String userId, String message) {
}
