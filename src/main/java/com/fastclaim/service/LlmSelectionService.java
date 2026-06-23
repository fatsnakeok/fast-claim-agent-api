package com.fastclaim.service;

import com.embabel.common.ai.model.LlmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM 模型选择服务 — 将业务场景映射到逻辑角色，由 Embabel 框架根据 application.yml 解析为具体模型名。
 * 逻辑角色与物理模型解耦，换模型只需改配置，代码无感知。
 */
@Service
public class LlmSelectionService {

    private static final Logger log = LoggerFactory.getLogger(LlmSelectionService.class);

    public static final String ROLE_FAST      = "fast";
    public static final String ROLE_BALANCED  = "balanced";
    public static final String ROLE_POWERFUL  = "powerful";
    public static final String ROLE_EMBEDDING = "embedding";

    // 简单查询 → fast：响应快、成本低，适合单轮问答
    public LlmOptions forSimpleQuery() {
        return LlmOptions.withLlmForRole(ROLE_FAST);
    }

    // RAG 检索 → fast：检索任务不需要深度推理，快速模型即可
    public LlmOptions forRetrieval() {
        return LlmOptions.withLlmForRole(ROLE_FAST);
    }

    // 文档摘要 → balanced：摘要需要一定的理解能力，但不能太慢
    public LlmOptions forSummarization() {
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    // 复杂推理 → powerful：多步骤决策、逻辑推演，推理能力优先
    public LlmOptions forComplexReasoning() {
        return LlmOptions.withLlmForRole(ROLE_POWERFUL);
    }

    // 核保决策 → balanced：平衡准确性和响应时间
    public LlmOptions forUnderwriting() {
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    // 理赔处理 → balanced：需理解保单条款和事故描述，但不需要极深推理
    public LlmOptions forClaims() {
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    // 客服对话 → balanced：自然对话体验优先，推理需求适中
    public LlmOptions forChat() {
        return LlmOptions.withLlmForRole(ROLE_BALANCED);
    }

    // 嵌入操作 → embedding：预留，用于向量化场景
    public LlmOptions forEmbedding() {
        return LlmOptions.withLlmForRole(ROLE_EMBEDDING);
    }

    // 框架自动选择 → auto：由 Embabel 框架根据任务上下文自行判断
    public LlmOptions forAuto() {
        return LlmOptions.withAutoLlm();
    }

    // 按模型名指定 → 用于需要直接控制具体模型的特殊场景
    public LlmOptions forModel(String modelName) {
        log.debug("按模型名指定 LLM: {}", modelName);
        return LlmOptions.withModel(modelName);
    }

    // 按复杂度分数动态路由 — 0-30 简单→fast，31-60 中等→balanced，61-100 复杂→powerful
    public LlmOptions forComplexity(int score) {
        if (score <= 30) {
            log.debug("复杂度评分 {} → 路由到 fast 模型", score);
            return LlmOptions.withLlmForRole(ROLE_FAST);
        } else if (score <= 60) {
            log.debug("复杂度评分 {} → 路由到 balanced 模型", score);
            return LlmOptions.withLlmForRole(ROLE_BALANCED);
        } else {
            log.debug("复杂度评分 {} → 路由到 powerful 模型", score);
            return LlmOptions.withLlmForRole(ROLE_POWERFUL);
        }
    }
}
