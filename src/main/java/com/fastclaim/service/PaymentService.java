package com.fastclaim.service;

import com.fastclaim.entity.Policy;
import com.fastclaim.entity.Quote;
import com.fastclaim.entity.enums.PolicyStatus;
import com.fastclaim.entity.enums.QuoteStatus;
import com.fastclaim.repository.PolicyRepository;
import com.fastclaim.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final QuoteRepository quoteRepository;
    private final PolicyRepository policyRepository;

    public PaymentService(QuoteRepository quoteRepository, PolicyRepository policyRepository) {
        this.quoteRepository = quoteRepository;
        this.policyRepository = policyRepository;
    }

    /**
     * 模拟支付处理并签发保单
     */
    public Policy pay(Long quoteId) {
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> new BizException("报价单不存在: " + quoteId));
        log.info("支付处理 — quoteId: {}, status: {}, premium: {}",
                quoteId, quote.getStatus(), quote.getPremiumAmount());

        if (quote.getStatus() != QuoteStatus.APPROVED) {
            throw new BizException("仅已批准的报价单可支付，当前状态: " + quote.getStatus());
        }

        if (quote.getExpiresAt() != null && quote.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException("报价单已过期，有效期至: " + quote.getExpiresAt());
        }

        log.info("模拟支付成功 — quoteId: {}, amount: {}", quoteId, quote.getPremiumAmount());

        Policy policy = new Policy();
        policy.setCustomer(quote.getCustomer());
        policy.setVehicle(quote.getVehicle());
        policy.setCoverageType(quote.getCoverageType());
        policy.setPremiumAmount(quote.getPremiumAmount());
        policy.setEffectiveDate(LocalDateTime.now());
        int daysInYear = LocalDate.now().lengthOfYear();
        policy.setExpirationDate(policy.getEffectiveDate().plusDays(daysInYear));
        policy.setStatus(PolicyStatus.ACTIVE);

        Policy saved = policyRepository.save(policy);
        log.info("保单签发成功 — policyNumber: {}, premium: {}",
                saved.getPolicyNumber(), saved.getPremiumAmount());
        return saved;
    }
}
