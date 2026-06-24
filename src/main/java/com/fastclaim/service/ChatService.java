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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@EnableScheduling
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    private final AgentPlatform agentPlatform;
    private final InsuranceUserInputGuardRailImpl inputGuardRail;
    private final InsuranceAssistantMessageGuardRailImpl outputGuardRail;
    private final InsuranceKnowledgeBase knowledgeBase;
    private final HttpClient deepseekHttpClient;
    private final String deepseekApiKey;
    private final String deepseekBaseUrl;

    @Value("${chat.session.ttl-minutes:30}")
    private int sessionTtlMinutes;

    @Value("${chat.session.max-history-rounds:20}")
    private int maxHistoryRounds;

    @Value("${deepseek.stream.model:deepseek-chat}")
    private String streamModel;

    // DeepSeek SSE delta content 提取正则
    private static final Pattern DELTA_CONTENT_PATTERN =
            Pattern.compile("\"content\"\\s*:\\s*\"([^\"]*)\"");

    public ChatService(AgentPlatform agentPlatform,
                       InsuranceUserInputGuardRailImpl inputGuardRail,
                       InsuranceAssistantMessageGuardRailImpl outputGuardRail,
                       InsuranceKnowledgeBase knowledgeBase,
                       @Value("${deepseek.stream.base-url:https://api.deepseek.com}") String baseUrl,
                       @Value("${embabel.agent.platform.models.deepseek.api-key}") String apiKey) {
        this.agentPlatform = agentPlatform;
        this.inputGuardRail = inputGuardRail;
        this.outputGuardRail = outputGuardRail;
        this.knowledgeBase = knowledgeBase;
        this.deepseekBaseUrl = baseUrl;
        this.deepseekApiKey = apiKey;
        this.deepseekHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
     * 流式处理用户消息 — SSE 协议逐 token 推送 LLM 回答。
     * 会话管理、护栏校验与 processChat 一致。
     * RAG 由 InsuranceKnowledgeBase 程序化检索（非 Agent 工具调用），检索结果直接注入 prompt。
     * 会话保存在 SSE 流完成后异步执行。
     */
    public SseEmitter streamChat(String message, String sessionId) {
        String userId = getCurrentUserId();

        // 1. 获取或创建会话
        boolean isNewSession = false;
        ChatSession session;
        if (sessionId == null || sessionId.isEmpty()) {
            session = createSession(userId);
            sessionId = session.getSessionId();
            isNewSession = true;
            log.info("新建会话(流式) — userId: {}, sessionId: {}", userId, sessionId);
        } else {
            session = sessions.get(sessionId);
            if (session == null) {
                throw new SessionNotFoundException("会话不存在: " + sessionId);
            }
            if (session.isExpired(sessionTtlMinutes)) {
                log.info("会话已过期(流式) — sessionId: {}", sessionId);
                throw new SessionExpiredException("会话已过期，请重新发起对话");
            }
        }
        // 捕获 final 引用供 lambda 使用
        final String sid = sessionId;

        // 2. 输入护栏
        ValidationResult inputResult = inputGuardRail.validate(message, null);
        if (!inputResult.isValid()) {
            String reason = inputResult.getErrors().isEmpty()
                    ? "未知原因"
                    : inputResult.getErrors().get(0).getMessage();
            log.warn("输入护栏拒绝(流式) — sessionId: {}, reason: {}", sid, reason);
            throw new InputRejectedException(reason);
        }

        // 3. RAG 检索 — 将知识库结果直接注入 prompt（替代 Agent 工具调用模式）
        String ragContext = knowledgeBase.search(message);
        log.debug("流式 RAG 检索结果长度: {} 字符", ragContext.length());

        // 4. 构造 prompt
        String prompt = buildStreamPrompt(message, session.getConversationContext(maxHistoryRounds), ragContext);

        // 5. 创建 SseEmitter（超时与会话 TTL 一致）
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(sessionTtlMinutes));

        // 6. 首次会话先发送 metadata 事件，告知客户端 sessionId
        if (isNewSession) {
            try {
                emitter.send(SseEmitter.event()
                        .name("metadata")
                        .data("{\"sessionId\":\"" + sid + "\",\"isNewSession\":true}"));
            } catch (IOException e) {
                log.error("SSE metadata 发送失败 — sessionId: {}", sid, e);
                emitter.completeWithError(e);
                return emitter;
            }
        }

        // 7. 构造 JSON 请求体
        // 对 prompt 中的特殊字符做 JSON 转义
        String escapedPrompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String requestJson = "{\"model\":\"" + streamModel + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}],"
                + "\"stream\":true}";

        log.debug("流式请求 DeepSeek API — URL: {}/v1/chat/completions, model: {}",
                deepseekBaseUrl, streamModel);

        // 8. 异步调用 DeepSeek API（java.net.http.HttpClient，不依赖 Netty）
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);
        StringBuilder fullAnswer = new StringBuilder();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(deepseekBaseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + deepseekApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofSeconds(120))
                .build();

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<Stream<String>> response = deepseekHttpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());

                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    log.error("DeepSeek API 返回错误状态码: {}", statusCode);
                    if (emitterCompleted.compareAndSet(false, true)) {
                        emitter.completeWithError(
                                new RuntimeException("DeepSeek API 返回 " + statusCode));
                    }
                    return;
                }

                response.body().forEach(line -> {
                    if (emitterCompleted.get()) {
                        return; // emitter 已被超时/错误/客户端断开完成，停止处理
                    }
                    if (!line.startsWith("data: ")) {
                        return;
                    }
                    String data = line.substring(6).trim();

                    if ("[DONE]".equals(data)) {
                        session.addMessagePair(message, fullAnswer.toString(), maxHistoryRounds);
                        session.touch();
                        safeSend(emitter, SseEmitter.event().name("done").data("[DONE]"), emitterCompleted);
                        log.debug("流式回答完成 — sessionId: {}, 回答长度: {} 字符",
                                sid, fullAnswer.length());
                        return;
                    }

                    String token = extractDeltaContent(data);
                    if (token != null && !token.isEmpty()) {
                        fullAnswer.append(token);
                        safeSend(emitter, SseEmitter.event().data(token), emitterCompleted);
                    }
                });

                // 流正常结束但未收到 [DONE]（DeepSeek 偶发不发送结束标记）
                if (emitterCompleted.get()) {
                    return;
                }
                if (fullAnswer.isEmpty()) {
                    log.warn("DeepSeek 流式响应为空 — sessionId: {}", sid);
                }
                if (!fullAnswer.isEmpty()) {
                    session.addMessagePair(message, fullAnswer.toString(), maxHistoryRounds);
                    session.touch();
                }
                safeSend(emitter, SseEmitter.event().name("done").data("[DONE]"), emitterCompleted);
                if (emitterCompleted.compareAndSet(false, true)) {
                    emitter.complete();
                }

            } catch (IOException e) {
                log.error("DeepSeek API 调用 IO 异常 — sessionId: {}", sid, e);
                if (emitterCompleted.compareAndSet(false, true)) {
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                log.error("流式调用失败 — sessionId: {}", sid, e);
                if (emitterCompleted.compareAndSet(false, true)) {
                    emitter.completeWithError(e);
                }
            }
        });

        // 注册超时和错误回调
        emitter.onTimeout(() -> {
            if (emitterCompleted.compareAndSet(false, true)) {
                log.warn("SSE 超时 — sessionId: {}, 已生成 {} 字符", sid, fullAnswer.length());
                if (!fullAnswer.isEmpty()) {
                    session.addMessagePair(message, fullAnswer.toString(), maxHistoryRounds);
                    session.touch();
                }
                emitter.complete();
            }
        });
        emitter.onError(ex -> {
            if (emitterCompleted.compareAndSet(false, true)) {
                log.error("SSE 异常 — sessionId: {}", sid, ex);
            }
        });

        return emitter;
    }

    /**
     * 安全发送 SSE 事件 — 防止 emitter 已完成时继续写入导致 IllegalStateException。
     */
    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event, AtomicBoolean completed) {
        if (completed.get()) {
            return;
        }
        try {
            emitter.send(event);
        } catch (IllegalStateException e) {
            log.debug("SSE send 失败 — emitter 已完成，跳过");
            completed.set(true);
        } catch (IOException e) {
            log.error("SSE send IO 异常", e);
            if (completed.compareAndSet(false, true)) {
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * 构造流式 prompt — 将 RAG 检索结果直接注入，无需 LLM 再次调用工具。
     */
    private String buildStreamPrompt(String message, String history, String ragContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的保险客服助手。请基于以下知识库文档回答用户问题。\n\n");
        sb.append("回答格式要求：\n");
        sb.append("- 段落之间用空行分隔\n");
        sb.append("- 流程步骤使用\"1、2、3、\"顺序编号，全篇编号从头到尾连续递增，禁止出现多个1、\n");
        sb.append("- 主要分类使用\"一、二、三、\"作为小标题\n");
        sb.append("- 关键术语使用【】包裹以突出显示\n");
        sb.append("- 严禁使用任何英文符号标记（##、---、**、>、``` 等），回答中不应出现这类符号\n");
        sb.append("- 使用中文回答，简洁明了\n\n");

        if (ragContext != null && !ragContext.isEmpty() && !"未找到相关文档内容".equals(ragContext)) {
            sb.append("相关保险知识库内容：\n");
            sb.append(ragContext);
            sb.append("\n\n");
        }

        if (history != null && !history.isEmpty()) {
            sb.append("对话历史：\n");
            sb.append(history);
            sb.append("\n\n");
        }

        sb.append("用户问题：");
        sb.append(message);
        return sb.toString();
    }

    /**
     * 从 DeepSeek SSE data 行提取 content delta token。
     * 响应格式: {"choices":[{"delta":{"content":"token文本"}}]}
     * 返回 null 表示此行不是 content delta（如 finish_reason 事件）。
     */
    private String extractDeltaContent(String jsonData) {
        try {
            Matcher matcher = DELTA_CONTENT_PATTERN.matcher(jsonData);
            if (matcher.find()) {
                String content = matcher.group(1);
                return content.replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
        } catch (Exception e) {
            log.debug("SSE 数据解析失败: {}", jsonData);
        }
        return null;
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
