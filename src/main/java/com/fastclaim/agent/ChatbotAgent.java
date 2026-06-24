package com.fastclaim.agent;

import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.core.AgentProcess;
import com.fastclaim.dto.ChatOutput;
import com.fastclaim.dto.UserInput;
import com.fastclaim.service.LlmSelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Agent(description = "保险 AI 客服", planner = PlannerType.UTILITY)
public class ChatbotAgent implements StuckHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatbotAgent.class);

    private final LlmReference insuranceRag;
    private final LlmSelectionService llmService;

    public ChatbotAgent(LlmReference insuranceRag, LlmSelectionService llmService) {
        this.insuranceRag = insuranceRag;
        this.llmService = llmService;
    }

    /**
     * 单 @Action — Agentic RAG 模式。
     * LLM 通过 insuranceRag 工具自主检索知识库，综合检索结果和对话历史生成回答。
     */
    @Action
    @AchievesGoal(description = "根据用户问题和知识库文档生成保险客服回答")
    public ChatOutput answerQuestion(UserInput input, OperationContext context) {
        log.debug("ChatbotAgent 处理用户消息: {}",
                input.message().substring(0, Math.min(50, input.message().length())));

        String answer = context.ai()
                .withLlm(llmService.forChat())
                .withReference(insuranceRag)
                .generateText(buildPrompt(input));

        log.debug("ChatbotAgent 生成回答长度: {} 字符", answer.length());
        return new ChatOutput(answer);
    }

    /**
     * 构造包含系统角色设定和对话历史的完整 prompt。
     */
    private String buildPrompt(UserInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的保险客服助手。请基于知识库文档回答用户问题。\n");
        sb.append("回答要求：\n");
        sb.append("1. 使用中文回答\n");
        sb.append("2. 引用具体的条款或规定\n");
        sb.append("3. 如信息不足，明确告知用户\n");
        sb.append("4. 回答简洁明了，避免冗长\n\n");

        if (input.conversationContext() != null && !input.conversationContext().isEmpty()) {
            sb.append("对话历史：\n");
            sb.append(input.conversationContext());
            sb.append("\n\n");
        }

        sb.append("用户问题：");
        sb.append(input.message());
        return sb.toString();
    }

    @Override
    public StuckHandlerResult handleStuck(AgentProcess agentProcess) {
        log.warn("ChatbotAgent 超时 STUCK — AgentProcess: {}", agentProcess);
        return new StuckHandlerResult(
                "ChatbotAgent stuck — no resolution available",
                this,
                com.embabel.agent.api.common.StuckHandlingResultCode.NO_RESOLUTION,
                agentProcess
        );
    }
}
