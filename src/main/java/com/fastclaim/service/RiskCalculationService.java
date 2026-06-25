package com.fastclaim.service;

import com.fastclaim.entity.Customer;
import com.fastclaim.entity.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class RiskCalculationService {

    private static final Logger log = LoggerFactory.getLogger(RiskCalculationService.class);

    static final double LOW_RISK_THRESHOLD = 60.0;
    static final double HIGH_RISK_THRESHOLD = 80.0;

    private static final int AGE_YOUNG_SCORE = 25;
    private static final int AGE_MID_SCORE = 15;
    private static final int AGE_SENIOR_SCORE = 20;
    private static final int EXP_LOW_SCORE = 20;
    private static final int EXP_MID_SCORE = 10;
    private static final int EXP_HIGH_BONUS = -10;
    private static final int ACCIDENT_PER_SCORE = 15;
    private static final int VEHICLE_AGE_OLD_SCORE = 15;
    private static final int VEHICLE_AGE_MID_SCORE = 8;
    private static final int HIGH_VALUE_SCORE = 10;
    private static final double HIGH_VALUE_THRESHOLD = 500_000.0;

    /**
     * 计算风险评分，结果钳制在 [0, 100]
     */
    public double calculate(Customer customer, Vehicle vehicle) {
        double score = 0.0;

        int age = customer.getAge();
        if (age < 25) {
            score += AGE_YOUNG_SCORE;
        } else if (age < 36) {
            score += AGE_MID_SCORE;
        } else if (age >= 65) {
            score += AGE_SENIOR_SCORE;
        }

        int drivingYears = customer.getDrivingExperienceYears();
        if (drivingYears < 3) {
            score += EXP_LOW_SCORE;
        } else if (drivingYears <= 5) {
            score += EXP_MID_SCORE;
        } else if (drivingYears >= 20) {
            score += EXP_HIGH_BONUS;
        }

        score += customer.getAccidentCount() * ACCIDENT_PER_SCORE;

        int vehicleAge = LocalDate.now().getYear() - vehicle.getYear();
        if (vehicleAge > 10) {
            score += VEHICLE_AGE_OLD_SCORE;
        } else if (vehicleAge >= 6) {
            score += VEHICLE_AGE_MID_SCORE;
        }

        if (vehicle.getVehicleValue() > HIGH_VALUE_THRESHOLD) {
            score += HIGH_VALUE_SCORE;
        }

        double clamped = Math.max(0.0, Math.min(100.0, score));
        log.debug("风险评分计算 — customer: {}, age: {}, drivingYears: {}, accidents: {}, "
                        + "vehicleAge: {}, vehicleValue: {}, rawScore: {}, clamped: {}",
                customer.getUserId(), age, drivingYears, customer.getAccidentCount(),
                vehicleAge, vehicle.getVehicleValue(), score, clamped);
        return clamped;
    }

    public boolean isLowRisk(double riskScore) {
        return riskScore <= LOW_RISK_THRESHOLD;
    }

    public boolean isHighRisk(double riskScore) {
        return riskScore >= HIGH_RISK_THRESHOLD;
    }
}
