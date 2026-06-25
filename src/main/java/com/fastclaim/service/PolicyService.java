package com.fastclaim.service;

import com.fastclaim.entity.Policy;
import com.fastclaim.repository.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository policyRepository;

    public PolicyService(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /**
     * 按 userId 查询保单列表，未找到返回空列表
     */
    public List<Policy> findByUserId(String userId) {
        List<Policy> policies = policyRepository.findByCustomer_UserId(userId);
        log.debug("保单查询 — userId: {}, 数量: {}", userId, policies.size());
        return policies;
    }

    /**
     * 按保单号精确查询
     */
    public Policy findByPolicyNumber(String policyNumber) {
        return policyRepository.findByPolicyNumber(policyNumber)
                .orElseThrow(() -> new BizException("保单不存在: " + policyNumber));
    }
}
