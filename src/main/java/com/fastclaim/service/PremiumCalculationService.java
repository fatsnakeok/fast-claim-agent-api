package com.fastclaim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PremiumCalculationService {

    private static final Logger log = LoggerFactory.getLogger(PremiumCalculationService.class);

    private static final double BASE_RATE = 0.02;
    private static final double COEF_THIRD_PARTY = 0.5;
    private static final double COEF_THIRD_PARTY_FIRE_THEFT = 0.75;
    private static final double COEF_COMPREHENSIVE = 1.0;
    private static final double RISK_LOW_BOUND = 40.0;
    private static final double RISK_HIGH_BOUND = 70.0;

    /**
     * 保费 = 车辆价值 × 基础费率 × 风险系数 × 险种系数
     */
    public double calculate(double vehicleValue, double riskScore, String coverageType) {
        BigDecimal baseValue = BigDecimal.valueOf(vehicleValue);
        BigDecimal baseRate = BigDecimal.valueOf(BASE_RATE);
        BigDecimal riskCoef = BigDecimal.valueOf(getRiskCoefficient(riskScore));
        BigDecimal coverageCoef = BigDecimal.valueOf(getCoverageCoefficient(coverageType));

        BigDecimal premium = baseValue
                .multiply(baseRate)
                .multiply(riskCoef)
                .multiply(coverageCoef)
                .setScale(2, RoundingMode.HALF_UP);

        log.debug("保费计算 — vehicleValue: {}, riskScore: {}, coverageType: {}, "
                        + "riskCoef: {}, coverageCoef: {}, premium: {}",
                vehicleValue, riskScore, coverageType,
                riskCoef.doubleValue(), coverageCoef.doubleValue(), premium.doubleValue());
        return premium.doubleValue();
    }

    private double getRiskCoefficient(double riskScore) {
        if (riskScore < RISK_LOW_BOUND) {
            return 0.8;
        } else if (riskScore < RISK_HIGH_BOUND) {
            return 1.0;
        } else {
            return 1.5;
        }
    }

    private double getCoverageCoefficient(String coverageType) {
        if (coverageType == null) {
            return COEF_COMPREHENSIVE;
        }
        return switch (coverageType) {
            case "THIRD_PARTY" -> COEF_THIRD_PARTY;
            case "THIRD_PARTY_FIRE_THEFT" -> COEF_THIRD_PARTY_FIRE_THEFT;
            case "COMPREHENSIVE" -> COEF_COMPREHENSIVE;
            default -> {
                log.warn("未知险种类型: {}，默认使用 COMPREHENSIVE 系数", coverageType);
                yield COEF_COMPREHENSIVE;
            }
        };
    }
}
