package com.fastclaim.controller;

import com.fastclaim.dto.*;
import com.fastclaim.entity.Policy;
import com.fastclaim.entity.Quote;
import com.fastclaim.service.AgentService;
import com.fastclaim.service.BizException;
import com.fastclaim.service.PaymentService;
import com.fastclaim.service.PolicyService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 保险服务
 */
@RestController
@RequestMapping("/api/insurance")
public class InsuranceController {

    private static final Logger log = LoggerFactory.getLogger(InsuranceController.class);

    private final AgentService agentService;
    private final PolicyService policyService;
    private final PaymentService paymentService;

    public InsuranceController(AgentService agentService, PolicyService policyService,
                                PaymentService paymentService) {
        this.agentService = agentService;
        this.policyService = policyService;
        this.paymentService = paymentService;
    }

    /**
     * 提交核保申请
     */
    @PostMapping("/underwrite")
    @PreAuthorize("hasAuthority('underwriting:write')")
    public ResponseEntity<UnderwritingResult> underwrite(@Valid @RequestBody UnderwriteRequest request) {
        log.info("核保申请 — userId: {}, input: {}",
                request.userId(),
                request.userInput().substring(0, Math.min(50, request.userInput().length())));
        UnderwritingResult result = agentService.processUnderwriting(
                request.userId(), request.userInput());

        if ("ERROR".equals(result.status())) {
            return ResponseEntity.unprocessableEntity().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 查询用户保单列表
     */
    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('policies:read')")
    public ResponseEntity<List<PolicyResponse>> listPolicies(@RequestParam String userId) {
        log.debug("查询保单列表 — userId: {}", userId);
        List<PolicyResponse> policies = policyService.findByUserId(userId)
                .stream()
                .map(PolicyResponse::from)
                .toList();
        return ResponseEntity.ok(policies);
    }

    /**
     * 按保单号查询保单详情
     */
    @GetMapping("/policies/{policyNumber}")
    @PreAuthorize("hasAuthority('policies:read')")
    public ResponseEntity<PolicyResponse> getPolicy(@PathVariable String policyNumber) {
        log.debug("查询保单详情 — policyNumber: {}", policyNumber);
        Policy policy = policyService.findByPolicyNumber(policyNumber);
        return ResponseEntity.ok(PolicyResponse.from(policy));
    }

    /**
     * 人工审批 — 将 REFERRED 报价单转为 APPROVED
     */
    @PostMapping("/quotes/{quoteId}/approve")
    @PreAuthorize("hasAuthority('underwriting:approve')")
    public ResponseEntity<ApproveQuoteResponse> approveQuote(
            @PathVariable Long quoteId,
            @RequestBody(required = false) ApproveQuoteRequest request) {
        log.info("人工审批 — quoteId: {}", quoteId);
        Double overridePremium = request != null ? request.overridePremiumAmount() : null;
        Quote quote = agentService.approveQuote(quoteId, overridePremium);
        return ResponseEntity.ok(new ApproveQuoteResponse(
                quote.getId(), quote.getStatus().name(),
                quote.getPremiumAmount(), "审批通过，报价单已批准"));
    }

    /**
     * 支付保费并签发保单
     */
    @PostMapping("/pay")
    @PreAuthorize("hasAuthority('underwriting:write')")
    public ResponseEntity<PolicyResponse> pay(@Valid @RequestBody PayRequest request) {
        log.info("支付请求 — quoteId: {}", request.quoteId());
        Policy policy = paymentService.pay(request.quoteId());
        return ResponseEntity.ok(PolicyResponse.from(policy));
    }

    /**
     * 健康检查 — 无需认证
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
