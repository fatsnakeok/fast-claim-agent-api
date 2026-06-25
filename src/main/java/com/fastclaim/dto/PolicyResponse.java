package com.fastclaim.dto;

import com.fastclaim.entity.Policy;

import java.time.LocalDateTime;

public record PolicyResponse(
        String policyNumber,
        String customerName,
        String vehicleModel,
        String coverageType,
        double premiumAmount,
        LocalDateTime effectiveDate,
        LocalDateTime expirationDate,
        String status
) {
    public static PolicyResponse from(Policy policy) {
        return new PolicyResponse(
                policy.getPolicyNumber(),
                policy.getCustomer().getName(),
                policy.getVehicle().getBrand() + " " + policy.getVehicle().getModel(),
                policy.getCoverageType(),
                policy.getPremiumAmount(),
                policy.getEffectiveDate(),
                policy.getExpirationDate(),
                policy.getStatus().name()
        );
    }
}
