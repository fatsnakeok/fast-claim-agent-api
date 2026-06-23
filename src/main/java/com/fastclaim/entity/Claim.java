package com.fastclaim.entity;

import com.fastclaim.entity.enums.ClaimStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Entity
@Table(name = "claim")
public class Claim {

    private static final Logger log = LoggerFactory.getLogger(Claim.class);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String claimNumber;

    @ManyToOne
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    @Column(nullable = false)
    private double claimedAmount;

    private double paidAmount;

    @Column(nullable = false)
    private double fraudScore;

    @Column(length = 2000, nullable = false)
    private String description;

    private LocalDateTime createdAt;

    private String processId;

    // 构造函数自动生成理赔单号与创建时间 — 单号格式 CLM-{8位随机大写}，纯随机保证唯一性
    public Claim() {
        this.claimNumber = generateClaimNumber();
        this.createdAt = LocalDateTime.now();
        log.debug("创建理赔单，编号: {}", this.claimNumber);
    }

    private static String generateClaimNumber() {
        String random = ThreadLocalRandom.current()
                .ints(8, 'A', 'Z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return "CLM-" + random;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClaimNumber() { return claimNumber; }
    public void setClaimNumber(String claimNumber) { this.claimNumber = claimNumber; }

    public Policy getPolicy() { return policy; }
    public void setPolicy(Policy policy) { this.policy = policy; }

    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }

    public double getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(double claimedAmount) { this.claimedAmount = claimedAmount; }

    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = paidAmount; }

    public double getFraudScore() { return fraudScore; }
    public void setFraudScore(double fraudScore) { this.fraudScore = fraudScore; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getProcessId() { return processId; }
    public void setProcessId(String processId) { this.processId = processId; }
}
