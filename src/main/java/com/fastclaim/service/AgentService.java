package com.fastclaim.service;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.fastclaim.dto.UnderwritingInput;
import com.fastclaim.dto.UnderwritingResult;
import com.fastclaim.entity.Quote;
import com.fastclaim.entity.enums.QuoteStatus;
import com.fastclaim.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.*;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentPlatform agentPlatform;
    private final QuoteRepository quoteRepository;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public AgentService(AgentPlatform agentPlatform, QuoteRepository quoteRepository) {
        this.agentPlatform = agentPlatform;
        this.quoteRepository = quoteRepository;
    }

    /**
     * 执行核保流程，120s 超时保护
     */
    public UnderwritingResult processUnderwriting(String userId, String userInput) {
        log.info("开始核保流程 — userId: {}, input: {}", userId,
                userInput.substring(0, Math.min(50, userInput.length())));

        UnderwritingInput input = new UnderwritingInput(userId, userInput);

        CompletableFuture<UnderwritingResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                // 调用智能代理平台执行核保流程，传入用户输入信息并返回核保结果
                return AgentInvocation.on(agentPlatform)
                        .returning(UnderwritingResult.class)
                        .invoke(input);
            } catch (Exception e) {
                log.error("核保流程执行异常 — userId: {}", userId, e);
                return UnderwritingResult.error("核保流程异常: " + e.getMessage());
            }
        }, executor);

        try {
            UnderwritingResult result = future.get(120, TimeUnit.SECONDS);
            if (result != null) {
                log.info("核保流程完成 — status: {}, quoteId: {}", result.status(), result.quoteId());
                return result;
            }
            log.warn("核保流程未产生 UnderwritingResult");
            return UnderwritingResult.error("核保流程未产生结果");

        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("核保流程超时（120s）— userId: {}", userId);
            return UnderwritingResult.error("核保流程超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("核保流程被中断 — userId: {}", userId, e);
            return UnderwritingResult.error("核保流程被中断");
        } catch (ExecutionException e) {
            log.error("核保流程执行异常 — userId: {}", userId, e.getCause());
            return UnderwritingResult.error(
                    "核保流程异常: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    /**
     * 人工审批 — 将 REFERRED 报价单转为 APPROVED
     */
    public Quote approveQuote(Long quoteId, Double overridePremium) {
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> new BizException("报价单不存在: " + quoteId));

        if (quote.getStatus() != QuoteStatus.REFERRED) {
            throw new BizException("仅转人工状态的报价单可审批，当前状态: " + quote.getStatus());
        }

        if (quote.getExpiresAt() != null && quote.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException("报价单已过期，有效期至: " + quote.getExpiresAt());
        }

        if (overridePremium != null && overridePremium > 0) {
            log.info("人工审批 — quoteId: {}, 保费覆盖: {} → {}", quoteId,
                    quote.getPremiumAmount(), overridePremium);
            quote.setPremiumAmount(overridePremium);
        }

        quote.setStatus(QuoteStatus.APPROVED);
        Quote saved = quoteRepository.save(quote);
        log.info("人工审批完成 — quoteId: {}, status: APPROVED, premium: {}",
                saved.getId(), saved.getPremiumAmount());
        return saved;
    }
}
