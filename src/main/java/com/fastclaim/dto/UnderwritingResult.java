package com.fastclaim.dto;

/**
 * 核保流程的最终产物。
 * 由 @AchievesGoal 方法返回，同时存入 Blackboard 供 AgentService 读取。
 */
public record UnderwritingResult(
        Long quoteId,
        String status,
        double riskScore,
        double premiumAmount,
        String message
) {
    public static UnderwritingResult of(Long quoteId, String status, double riskScore,
                                        double premiumAmount, String message) {
        return new UnderwritingResult(quoteId, status, riskScore, premiumAmount, message);
    }

    public static UnderwritingResult error(String message) {
        return new UnderwritingResult(null, "ERROR", 0.0, 0.0, message);
    }
}
