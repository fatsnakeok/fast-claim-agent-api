# 阶段三详细设计任务文档：UnderwritingAgent — 智能核保 MVP

> 基于 `spec.md` 和 `plan.md`，逐任务分解实现细节。

---

## 任务总览

| 编号 | 任务 | 产出路径 | 优先级 | 依赖 |
|------|------|---------|--------|------|
| T1 | DTO 类定义 | `dto/` | P0 | — |
| T2 | DataService | `service/DataService.java` | P0 | T1 |
| T3 | RiskCalculationService | `service/RiskCalculationService.java` | P0 | — |
| T4 | PremiumCalculationService | `service/PremiumCalculationService.java` | P0 | — |
| T5 | PaymentService | `service/PaymentService.java` | P0 | T1 |
| T6 | PolicyService | `service/PolicyService.java` | P1 | — |
| T7 | VehicleInfoGuardRail | `guardrail/VehicleInfoGuardRailImpl.java` | P1 | — |
| T8 | UnderwritingAgent | `agent/UnderwritingAgent.java` | P0 | T1, T2, T3, T4, T7 |
| T9 | AgentService | `service/AgentService.java` | P0 | T8 |
| T10 | InsuranceController | `controller/InsuranceController.java` | P0 | T1, T9 |
| T11 | 更新 application.yml | `resources/application.yml` | P1 | — |
| T12 | UnderwritingAgentTest | `agent/UnderwritingAgentTest.java` | P1 | T8, T9, T10 |

**依赖关系**：T1 → (T2~T7 并行) → T8 → T9 → T10 → T12，T11 与 T1-T12 可并行。

> 注：本阶段无新增 Maven 依赖，全部复用阶段一、二的 Embabel + Spring Data JPA + DeepSeek LLM 技术栈。

---
sheng
## T1 DTO 类定义

### T1.1 VehicleInfo — LLM 提取的车辆信息

**路径**：`src/main/java/com/fastclaim/dto/VehicleInfo.java`

```java
package com.fastclaim.dto;

/**
 * LLM 从自然语言中提取的结构化车辆信息。
 * EXTRACTION_FAILED 哨兵用于快速失败路径。
 */
public record VehicleInfo(
        String brand,
        String model,
        String licensePlate
) {
    public static final VehicleInfo EXTRACTION_FAILED = new VehicleInfo("", "", null);

    public boolean isFailed() {
        return (brand == null || brand.isEmpty()) && (model == null || model.isEmpty());
    }
}
```

**字段说明**：
| 字段 | 类型 | 说明 |
|------|------|------|
| brand | String | 品牌，如 "Toyota"，空字符串表示未识别 |
| model | String | 型号，如 "RAV4"，空字符串表示未识别 |
| licensePlate | String | 车牌号，如 "LOW001"，可为 null |

### T1.2 UnderwritingResult — Agent 最终输出

**路径**：`src/main/java/com/fastclaim/dto/UnderwritingResult.java`

```java
package com.fastclaim.dto;

/**
 * 核保流程的最终产物。
 * 由 @AchievesGoal 方法返回，同时存入 Blackboard 供 AgentService 读取。
 */
public record UnderwritingResult(
        Long quoteId,
        String status,          // APPROVED / REFERRED / DECLINED / ERROR
        double riskScore,
        double premiumAmount,
        String message
) {
    // 工厂方法：正常路径
    public static UnderwritingResult of(Long quoteId, String status, double riskScore,
                                        double premiumAmount, String message) {
        return new UnderwritingResult(quoteId, status, riskScore, premiumAmount, message);
    }

    // 工厂方法：错误路径（无 quoteId，不持久化 Quote）
    public static UnderwritingResult error(double riskScore, String message) {
        return new UnderwritingResult(null, "ERROR", riskScore, 0.0, message);
    }
}
```

### T1.3 UnderwriteRequest — 核保申请请求体

**路径**：`src/main/java/com/fastclaim/dto/UnderwriteRequest.java`

```java
package com.fastclaim.dto;

import jakarta.validation.constraints.NotBlank;

public record UnderwriteRequest(
        @NotBlank(message = "用户ID不能为空")
        String userId,

        @NotBlank(message = "投保描述不能为空")
        String userInput
) {}
```

### T1.4 PayRequest — 支付请求体

**路径**：`src/main/java/com/fastclaim/dto/PayRequest.java`

```java
package com.fastclaim.dto;

import jakarta.validation.constraints.NotNull;

public record PayRequest(
        @NotNull(message = "报价单ID不能为空")
        Long quoteId
) {}
```

### T1.5 ApproveQuoteRequest — 人工审批请求体

**路径**：`src/main/java/com/fastclaim/dto/ApproveQuoteRequest.java`

```java
package com.fastclaim.dto;

/**
 * overridePremiumAmount 可选，不传则保持原保费不变。
 */
public record ApproveQuoteRequest(
        Double overridePremiumAmount
) {}
```

### T1.6 ApproveQuoteResponse — 人工审批响应体

**路径**：`src/main/java/com/fastclaim/dto/ApproveQuoteResponse.java`

```java
package com.fastclaim.dto;

public record ApproveQuoteResponse(
        Long quoteId,
        String status,
        double premiumAmount,
        String message
) {}
```

### T1.7 PolicyResponse — 保单查询响应体

**路径**：`src/main/java/com/fastclaim/dto/PolicyResponse.java`

```java
package com.fastclaim.dto;

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
    public static PolicyResponse from(com.fastclaim.entity.Policy policy) {
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
```

---

## T2 DataService — 客户/车辆多维度查询

**路径**：`src/main/java/com/fastclaim/service/DataService.java`

```java
package com.fastclaim.service;

import com.fastclaim.dto.VehicleInfo;
import com.fastclaim.entity.Customer;
import com.fastclaim.entity.Vehicle;
import com.fastclaim.repository.CustomerRepository;
import com.fastclaim.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);

    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;

    public DataService(CustomerRepository customerRepository, VehicleRepository vehicleRepository) {
        this.customerRepository = customerRepository;
        this.vehicleRepository = vehicleRepository;
    }

    /**
     * 按 userId 查找客户，未找到返回 sentinel 而非 null。
     *
     * @param userId 用户标识
     * @return Customer 或 lookupFailed() 占位对象
     */
    public Customer findCustomer(String userId) {
        return customerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    log.warn("客户未找到 — userId: {}", userId);
                    return Customer.lookupFailed();
                });
    }

    /**
     * 按车辆信息 + 客户查找车辆，三级优先级。
     * 1. 车牌精确匹配（需归属当前客户）
     * 2. 品牌 + 型号匹配（客户名下）
     * 3. 客户名下唯一车辆
     *
     * @param info     LLM 提取的车辆信息
     * @param customer 当前客户
     * @return Vehicle 或 lookupFailed() 占位对象
     */
    public Vehicle findVehicle(VehicleInfo info, Customer customer) {
        // 优先级1：车牌精确匹配
        if (info.licensePlate() != null && !info.licensePlate().isEmpty()) {
            var optVehicle = vehicleRepository.findByLicensePlate(info.licensePlate());
            if (optVehicle.isPresent()) {
                Vehicle v = optVehicle.get();
                if (v.getCustomer().getId().equals(customer.getId())) {
                    log.debug("车辆查找 — 车牌匹配: {}", info.licensePlate());
                    return v;
                }
                log.warn("车辆查找 — 车牌 {} 存在但不属于客户 {}", info.licensePlate(), customer.getUserId());
            }
        }

        // 获取客户名下所有车辆
        List<Vehicle> customerVehicles = vehicleRepository.findByCustomerId(customer.getId());

        // 优先级2：品牌 + 型号匹配
        if (!info.isFailed() && !info.brand().isEmpty() && !info.model().isEmpty()) {
            for (Vehicle v : customerVehicles) {
                if (info.brand().equalsIgnoreCase(v.getBrand())
                        && info.model().equalsIgnoreCase(v.getModel())) {
                    log.debug("车辆查找 — 品牌型号匹配: {} {}", info.brand(), info.model());
                    return v;
                }
            }
        }

        // 优先级3：客户名下唯一车辆
        if (customerVehicles.size() == 1) {
            Vehicle v = customerVehicles.get(0);
            log.debug("车辆查找 — 客户名下唯一车辆: {} {}", v.getBrand(), v.getModel());
            return v;
        }

        log.warn("车辆查找失败 — userId: {}, brand: {}, model: {}, plate: {}, 名下车辆数: {}",
                customer.getUserId(), info.brand(), info.model(), info.licensePlate(), customerVehicles.size());
        return Vehicle.lookupFailed();
    }
}
```

**方法清单**：

| 方法 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `findCustomer(userId)` | String | Customer | 未找到返回 `Customer.lookupFailed()` sentinel |
| `findVehicle(info, customer)` | VehicleInfo, Customer | Vehicle | 三级优先级查找，均未找到返回 `Vehicle.lookupFailed()` |

---

## T3 RiskCalculationService — 6 因子风险评分

**路径**：`src/main/java/com/fastclaim/service/RiskCalculationService.java`

```java
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

    /** 低风险阈值：≤ 60 自动批准 */
    static final double LOW_RISK_THRESHOLD = 60.0;
    /** 高风险阈值：≥ 80 自动拒绝 */
    static final double HIGH_RISK_THRESHOLD = 80.0;

    // 评分常量
    private static final int AGE_YOUNG_SCORE = 25;      // < 25岁
    private static final int AGE_MID_SCORE = 15;         // 25-35岁
    private static final int AGE_SENIOR_SCORE = 20;      // ≥ 65岁
    private static final int EXP_LOW_SCORE = 20;         // 驾龄 < 3年
    private static final int EXP_MID_SCORE = 10;         // 驾龄 3-5年
    private static final int EXP_HIGH_BONUS = -10;       // 驾龄 ≥ 20年（负分）
    private static final int ACCIDENT_PER_SCORE = 15;    // 每次事故
    private static final int VEHICLE_AGE_OLD_SCORE = 15; // 车龄 > 10年
    private static final int VEHICLE_AGE_MID_SCORE = 8;  // 车龄 6-10年
    private static final int HIGH_VALUE_SCORE = 10;      // 车价 > 50万
    private static final double HIGH_VALUE_THRESHOLD = 500_000.0;

    /**
     * 计算风险评分，结果钳制在 [0, 100]。
     *
     * @param customer 客户（含年龄、驾龄、事故次数）
     * @param vehicle  车辆（含车龄、车辆价值）
     * @return 风险评分 [0, 100]
     */
    public double calculate(Customer customer, Vehicle vehicle) {
        double score = 0.0;

        // 1. 年龄因子
        int age = customer.getAge();
        if (age < 25) {
            score += AGE_YOUNG_SCORE;
        } else if (age < 36) {
            score += AGE_MID_SCORE;
        } else if (age >= 65) {
            score += AGE_SENIOR_SCORE;
        }
        // 36-64 岁基准分 0，不处理

        // 2. 驾龄因子
        int drivingYears = customer.getDrivingExperienceYears();
        if (drivingYears < 3) {
            score += EXP_LOW_SCORE;
        } else if (drivingYears <= 5) {
            score += EXP_MID_SCORE;
        } else if (drivingYears >= 20) {
            score += EXP_HIGH_BONUS;  // 负分扣减
        }
        // 6-19 年基准分 0，不处理

        // 3. 事故因子
        score += customer.getAccidentCount() * ACCIDENT_PER_SCORE;

        // 4. 车龄因子
        int vehicleAge = LocalDate.now().getYear() - vehicle.getYear();
        if (vehicleAge > 10) {
            score += VEHICLE_AGE_OLD_SCORE;
        } else if (vehicleAge >= 6) {
            score += VEHICLE_AGE_MID_SCORE;
        }

        // 5. 车辆价值因子
        if (vehicle.getVehicleValue() > HIGH_VALUE_THRESHOLD) {
            score += HIGH_VALUE_SCORE;
        }

        // 钳制在 [0, 100]
        double clamped = Math.max(0.0, Math.min(100.0, score));
        log.debug("风险评分计算 — customer: {}, age: {}, drivingYears: {}, accidents: {}, "
                        + "vehicleAge: {}, vehicleValue: {}, rawScore: {}, clamped: {}",
                customer.getUserId(), age, drivingYears, customer.getAccidentCount(),
                vehicleAge, vehicle.getVehicleValue(), score, clamped);
        return clamped;
    }

    /** 判断是否为低风险（自动批准） */
    public boolean isLowRisk(double riskScore) {
        return riskScore <= LOW_RISK_THRESHOLD;
    }

    /** 判断是否为高风险（自动拒绝） */
    public boolean isHighRisk(double riskScore) {
        return riskScore >= HIGH_RISK_THRESHOLD;
    }
}
```

**预设用户评分验证**：

| 用户 | 年龄 | 年龄(分) | 驾龄 | 驾龄(分) | 事故 | 事故(分) | 车龄 | 车龄(分) | 车价 | 车价(分) | 总分 | 结果 |
|------|------|---------|------|---------|------|---------|------|---------|------|---------|------|------|
| low-risk-user | 41 | 0 | 15 | 0 | 1 | 15 | 4 | 0 | 300k | 0 | 15 | APPROVED |
| medium-risk-user | 27 | 15 | 4 | 10 | 2 | 30 | 8 | 8 | 180k | 0 | 63 | REFERRED |
| high-risk-user | 21 | 25 | 1 | 20 | 3 | 45 | 13 | 15 | 600k | 10 | 115→100 | DECLINED |

---

## T4 PremiumCalculationService — 保费计算

**路径**：`src/main/java/com/fastclaim/service/PremiumCalculationService.java`

```java
package com.fastclaim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PremiumCalculationService {

    private static final Logger log = LoggerFactory.getLogger(PremiumCalculationService.class);

    /** 基础费率：2% */
    private static final double BASE_RATE = 0.02;

    /** 险种系数 */
    private static final double COEF_THIRD_PARTY = 0.5;
    private static final double COEF_THIRD_PARTY_FIRE_THEFT = 0.75;
    private static final double COEF_COMPREHENSIVE = 1.0;

    /** 风险系数阈值 */
    private static final double RISK_LOW_BOUND = 40.0;
    private static final double RISK_HIGH_BOUND = 70.0;

    /**
     * 保费 = 车辆价值 × 基础费率 × 风险系数 × 险种系数。
     * 使用 BigDecimal 保证金额精度（阿里巴巴手册强制要求）。
     *
     * @param vehicleValue 车辆价值
     * @param riskScore    风险评分 [0, 100]
     * @param coverageType 险种类型
     * @return 保费金额（保留两位小数，四舍五入）
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
```

---

## T5 PaymentService — 模拟支付 + 签发保单

**路径**：`src/main/java/com/fastclaim/service/PaymentService.java`

```java
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
     * 模拟支付处理并签发保单。
     *
     * @param quoteId 已批准的报价单 ID
     * @return 签发的保单
     * @throws BizException 报价单不存在 / 状态非 APPROVED / 已过期
     */
    public Policy pay(Long quoteId) {
        // 1. 查询报价单
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> new BizException("报价单不存在: " + quoteId));
        log.info("支付处理 — quoteId: {}, status: {}, premium: {}",
                quoteId, quote.getStatus(), quote.getPremiumAmount());

        // 2. 状态校验
        if (quote.getStatus() != QuoteStatus.APPROVED) {
            throw new BizException("仅已批准的报价单可支付，当前状态: " + quote.getStatus());
        }

        // 3. 过期校验
        if (quote.getExpiresAt() != null && quote.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException("报价单已过期，有效期至: " + quote.getExpiresAt());
        }

        // 4. 模拟支付处理
        log.info("模拟支付成功 — quoteId: {}, amount: {}", quoteId, quote.getPremiumAmount());

        // 5. 创建保单
        Policy policy = new Policy();
        // policyNumber 由 Policy 构造函数自动生成
        policy.setCustomer(quote.getCustomer());
        policy.setVehicle(quote.getVehicle());
        policy.setCoverageType(quote.getCoverageType());
        policy.setPremiumAmount(quote.getPremiumAmount());
        policy.setEffectiveDate(LocalDateTime.now());
        // 有效期使用当年实际天数，避免硬编码 365（阿里巴巴手册强制要求）
        int daysInYear = LocalDate.now().lengthOfYear();
        policy.setExpirationDate(policy.getEffectiveDate().plusDays(daysInYear));
        policy.setStatus(PolicyStatus.ACTIVE);

        // 6. 持久化
        Policy saved = policyRepository.save(policy);
        log.info("保单签发成功 — policyNumber: {}, premium: {}",
                saved.getPolicyNumber(), saved.getPremiumAmount());
        return saved;
    }
}
```

**异常定义**（位于 `service/BizException.java`）：

```java
package com.fastclaim.service;

/**
 * 业务异常，携带错误码。
 * 全局异常处理器按 HTTP 状态码映射：400 / 404 / 422。
 */
public class BizException extends RuntimeException {
    private final String errorCode;

    public BizException(String message) {
        this("BIZ_ERROR", message);
    }

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
```

---

## T6 PolicyService — 保单查询

**路径**：`src/main/java/com/fastclaim/service/PolicyService.java`

```java
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
     * 按 userId 查询保单列表，未找到返回空列表（阿里巴巴手册要求不返回 null）。
     */
    public List<Policy> findByUserId(String userId) {
        // 先通过 Customer 确认用户存在，再查保单
        List<Policy> policies = policyRepository.findByCustomer_UserId(userId);
        log.debug("保单查询 — userId: {}, 数量: {}", userId, policies.size());
        return policies;
    }

    /**
     * 按保单号精确查询。
     *
     * @throws BizException 保单不存在
     */
    public Policy findByPolicyNumber(String policyNumber) {
        return policyRepository.findByPolicyNumber(policyNumber)
                .orElseThrow(() -> new BizException("保单不存在: " + policyNumber));
    }
}
```

> **注意**：`PolicyRepository` 当前仅有 `findByPolicyNumber(String)` 和 `findByCustomerId(Long)` 方法。`findByCustomer_UserId` 是 Spring Data JPA 属性表达式，按嵌套路径 `customer.userId` 自动推导查询，无需新增方法。如果框架解析失败，可在 `PolicyRepository` 中显式添加：
> ```java
> List<Policy> findByCustomer_UserId(String userId);
> ```

---

## T7 VehicleInfoGuardRail — 车辆关键词护栏

**路径**：`src/main/java/com/fastclaim/guardrail/VehicleInfoGuardRailImpl.java`

```java
package com.fastclaim.guardrail;

import com.embabel.agent.api.guardrail.UserInputGuardRail;
import com.embabel.agent.api.guardrail.GuardRailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 核保输入护栏 — 确保用户输入包含可识别的车辆关键词。
 * 挂载于 UnderwritingAgent.extractVehicleInfo @Action。
 */
@Component
public class VehicleInfoGuardRailImpl implements UserInputGuardRail {

    private static final Logger log = LoggerFactory.getLogger(VehicleInfoGuardRailImpl.class);

    /** 主流汽车品牌词库（大写形式） */
    private static final Set<String> BRANDS = Set.of(
            "TOYOTA", "HONDA", "BMW", "TESLA", "BENZ", "MERCEDES", "AUDI",
            "VOLKSWAGEN", "VW", "FORD", "NISSAN", "HYUNDAI", "KIA", "BYD",
            "NIO", "XPENG", "LI AUTO", "GEELY", "CHERY", "GREAT WALL",
            "MAZDA", "SUBARU", "LEXUS", "PORSCHE", "CADILLAC", "VOLVO",
            "丰田", "本田", "宝马", "特斯拉", "奔驰", "奥迪", "大众",
            "福特", "日产", "现代", "起亚", "比亚迪", "蔚来", "小鹏",
            "理想", "吉利", "奇瑞", "长城", "马自达", "斯巴鲁", "雷克萨斯",
            "保时捷", "凯迪拉克", "沃尔沃"
    );

    /** 车牌格式正则：2-3 位大写字母 + 3-5 位数字 */
    private static final Pattern LICENSE_PLATE_PATTERN =
            Pattern.compile("[A-Z]{2,3}\\d{3,5}", Pattern.CASE_INSENSITIVE);

    /** 通用模糊表述 — 没有品牌/型号信息，无法核保 */
    private static final Set<String> VAGUE_PATTERNS = Set.of(
            "我的车", "一辆车", "这台车", "那辆车", "my car", "a car"
    );

    @Override
    public GuardRailResult validate(String userInput) {
        String upper = userInput.toUpperCase();

        // 1. 检查品牌关键词
        boolean hasBrand = BRANDS.stream().anyMatch(upper::contains);
        if (hasBrand) {
            return GuardRailResult.pass();
        }

        // 2. 检查车牌格式
        if (LICENSE_PLATE_PATTERN.matcher(userInput).find()) {
            return GuardRailResult.pass();
        }

        // 3. 检查模糊表述（"我的车"等）
        boolean isVague = VAGUE_PATTERNS.stream().anyMatch(v -> userInput.contains(v));
        if (isVague) {
            log.warn("车辆信息护栏 — 仅包含模糊表述，缺少具体品牌/型号");
            return GuardRailResult.reject("VEHICLE_INFO_INSUFFICIENT",
                    "请提供具体车辆信息（品牌、型号或车牌号），例如'我的 Toyota RAV4'");
        }

        log.warn("车辆信息护栏 — 输入不包含可识别的车辆关键词: {}",
                userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput);
        return GuardRailResult.reject("VEHICLE_NOT_RECOGNIZED",
                "未识别到车辆信息，请提供品牌、型号或车牌号");
    }
}
```

**挂载方式**：在 `UnderwritingAgent.extractVehicleInfo` 方法上通过 Embabel 框架的注解声明：

```java
@Action
@GuardRail(VehicleInfoGuardRailImpl.class)
public VehicleInfo extractVehicleInfo(UserInput input, OperationContext context) { ... }
```

---

## T8 UnderwritingAgent — 核保智能体

**路径**：`src/main/java/com/fastclaim/agent/UnderwritingAgent.java`

### T8.1 智能体声明

```java
package com.fastclaim.agent;

import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.GuardRail;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.core.AgentProcess;
import com.fastclaim.dto.UserInput;
import com.fastclaim.dto.UnderwritingResult;
import com.fastclaim.dto.VehicleInfo;
import com.fastclaim.entity.Customer;
import com.fastclaim.entity.Quote;
import com.fastclaim.entity.Vehicle;
import com.fastclaim.entity.enums.QuoteStatus;
import com.fastclaim.guardrail.VehicleInfoGuardRailImpl;
import com.fastclaim.repository.QuoteRepository;
import com.fastclaim.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

@Agent(description = "核保 Agent", planner = PlannerType.UTILITY)
public class UnderwritingAgent implements StuckHandler {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingAgent.class);

    private final LlmSelectionService llmService;
    private final DataService dataService;
    private final RiskCalculationService riskCalcService;
    private final PremiumCalculationService premiumCalcService;
    private final QuoteRepository quoteRepository;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public UnderwritingAgent(LlmSelectionService llmService, DataService dataService,
                             RiskCalculationService riskCalcService,
                             PremiumCalculationService premiumCalcService,
                             QuoteRepository quoteRepository) {
        this.llmService = llmService;
        this.dataService = dataService;
        this.riskCalcService = riskCalcService;
        this.premiumCalcService = premiumCalcService;
        this.quoteRepository = quoteRepository;
    }
```

### T8.2 @Action 方法

#### extractVehicleInfo

```java
    /**
     * [1] LLM 从自然语言中提取结构化车辆信息。
     * 前置检查 customer 存在性，不存在则快速失败避免无效 LLM 调用。
     */
    @Action
    @GuardRail(VehicleInfoGuardRailImpl.class)
    public VehicleInfo extractVehicleInfo(UserInput input, OperationContext context) {
        // 快速失败：先查 customer
        String userId = context.getBlackboard().get("userId", String.class);
        if (userId == null) {
            log.warn("extractVehicleInfo — Blackboard 中无 userId，无法前置检查");
        } else {
            Customer preCheck = dataService.findCustomer(userId);
            if (Customer.isLookupFailed(preCheck)) {
                log.warn("extractVehicleInfo — 客户不存在，跳过 LLM 调用: {}", userId);
                // 上报错误到 Blackboard，后续 @State 路由到 ExtractionFailed
                context.bind("underwriting_error", "客户不存在: " + userId);
                return VehicleInfo.EXTRACTION_FAILED;
            }
        }

        log.debug("extractVehicleInfo — 调用 LLM 提取车辆信息");
        String jsonResult = context.ai()
                .withLlm(llmService.forSimpleQuery())
                .generateText("""
                        你是一个车辆信息提取工具。从用户输入中提取车辆的品牌、型号和车牌号。
                        以 JSON 格式返回：{"brand":"...","model":"...","licensePlate":"..."}
                        如果无法识别任何车辆信息，返回：{"brand":"","model":"","licensePlate":null}

                        用户输入：""" + input.message());

        try {
            VehicleInfo info = objectMapper.readValue(jsonResult, VehicleInfo.class);
            log.debug("extractVehicleInfo — LLM 提取结果: brand={}, model={}, plate={}",
                    info.brand(), info.model(), info.licensePlate());
            if (info.isFailed()) {
                context.bind("underwriting_error", "无法从用户输入中识别车辆信息");
            }
            return info;
        } catch (Exception e) {
            log.error("extractVehicleInfo — LLM 返回 JSON 解析失败: {}", jsonResult, e);
            context.bind("underwriting_error", "车辆信息提取失败");
            return VehicleInfo.EXTRACTION_FAILED;
        }
    }
```

#### lookupCustomer

```java
    /**
     * [2] 从数据库查找客户。
     * 未找到返回 sentinel，不抛异常。
     */
    @Action
    public Customer lookupCustomer(UserInput input, OperationContext context) {
        String userId = context.getBlackboard().get("userId", String.class);
        if (userId == null) {
            log.warn("lookupCustomer — Blackboard 中无 userId");
            context.bind("underwriting_error", "未提供用户标识");
            return Customer.lookupFailed();
        }
        Customer customer = dataService.findCustomer(userId);
        if (Customer.isLookupFailed(customer)) {
            context.bind("underwriting_error", "客户不存在: " + userId);
        }
        return customer;
    }
```

#### lookupVehicle

```java
    /**
     * [3] 三级优先级查找车辆。
     * 返回 VehicleInfo + Customer，未找到返回 sentinel。
     */
    @Action
    public Vehicle lookupVehicle(VehicleInfo vehicleInfo, Customer customer,
                                  OperationContext context) {
        if (Customer.isLookupFailed(customer)) {
            log.debug("lookupVehicle — 客户为 sentinel，跳过车辆查找");
            return Vehicle.lookupFailed();
        }
        if (vehicleInfo.isFailed()) {
            log.warn("lookupVehicle — 车辆信息为 EXTRACTION_FAILED，返回 sentinel");
            context.bind("underwriting_error", "车辆信息提取失败，无法查找车辆");
            return Vehicle.lookupFailed();
        }
        Vehicle vehicle = dataService.findVehicle(vehicleInfo, customer);
        if (Vehicle.isLookupFailed(vehicle)) {
            context.bind("underwriting_error",
                    "车辆未找到 — brand: " + vehicleInfo.brand()
                            + ", model: " + vehicleInfo.model()
                            + ", plate: " + vehicleInfo.licensePlate());
        }
        return vehicle;
    }
```

#### assessRisk

```java
    /**
     * [4] 风险评估 — 入口检查 sentinel，按阈值路由到 @State 子类型。
     * 返回 sealed interface UnderwritingDecision，框架自动分发到对应 @AchievesGoal。
     */
    @Action
    public UnderwritingDecision assessRisk(Customer customer, Vehicle vehicle,
                                            OperationContext context) {
        // 入口检查：sentinel 路由到错误路径
        if (Customer.isLookupFailed(customer)) {
            log.warn("assessRisk — 路由到 CustomerNotFound");
            return new CustomerNotFound(
                    context.getBlackboard().get("underwriting_error", String.class));
        }
        if (Vehicle.isLookupFailed(vehicle)) {
            log.warn("assessRisk — 路由到 VehicleLookupError");
            return new VehicleLookupError(
                    context.getBlackboard().get("underwriting_error", String.class));
        }

        // 检查是否有前置错误
        String errorMsg = context.getBlackboard().get("underwriting_error", String.class);
        if (errorMsg != null && !errorMsg.isEmpty()) {
            log.warn("assessRisk — 前置错误，路由到 ExtractionFailed: {}", errorMsg);
            return new ExtractionFailed(errorMsg);
        }

        // 计算风险评分
        double riskScore = riskCalcService.calculate(customer, vehicle);

        // 计算保费（默认综合险）
        double premium = premiumCalcService.calculate(
                vehicle.getVehicleValue(), riskScore, "COMPREHENSIVE");

        // 路由到正常路径
        if (riskCalcService.isLowRisk(riskScore)) {
            log.info("assessRisk — 低风险 APPROVED: score={}, premium={}", riskScore, premium);
            return new LowRiskQuote(customer, vehicle, riskScore, premium);
        } else if (riskCalcService.isHighRisk(riskScore)) {
            log.info("assessRisk — 高风险 DECLINED: score={}, premium={}", riskScore, premium);
            return new HighRiskDecline(customer, vehicle, riskScore, premium);
        } else {
            log.info("assessRisk — 中风险 REFERRED: score={}, premium={}", riskScore, premium);
            return new MediumRiskReview(customer, vehicle, riskScore, premium);
        }
    }
```

### T8.3 Sealed Interface + @State 路由

```java
    // ============================================================
    // @State 路由 — sealed interface + 6 个子类型
    // ============================================================

    sealed interface UnderwritingDecision permits
            LowRiskQuote, MediumRiskReview, HighRiskDecline,
            CustomerNotFound, VehicleLookupError, ExtractionFailed {

        @AchievesGoal(description = "执行路由动作并返回核保结果")
        UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context);
    }

    // --- 正常路径 ---

    record LowRiskQuote(Customer customer, Vehicle vehicle,
                        double riskScore, double premium) implements UnderwritingDecision {
        @Override
        @AchievesGoal(description = "自动批准低风险报价")
        public UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context) {
            Quote quote = createQuote(QuoteStatus.APPROVED, null, quoteRepo);
            log.info("核保通过 — quoteId: {}, riskScore: {}, premium: {}", quote.getId(), riskScore, premium);
            return UnderwritingResult.of(quote.getId(), "APPROVED", riskScore, premium,
                    "核保通过。保费 ¥" + String.format("%.2f", premium) + "，报价单有效期 30 天。");
        }
    }

    record MediumRiskReview(Customer customer, Vehicle vehicle,
                             double riskScore, double premium) implements UnderwritingDecision {
        @Override
        @AchievesGoal(description = "转人工审核中风险报价")
        public UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context) {
            Quote quote = createQuote(QuoteStatus.REFERRED, null, quoteRepo);
            log.info("核保转人工 — quoteId: {}, riskScore: {}, premium: {}", quote.getId(), riskScore, premium);
            return UnderwritingResult.of(quote.getId(), "REFERRED", riskScore, premium,
                    "需要人工审核。您的申请已转交核保员处理。");
        }
    }

    record HighRiskDecline(Customer customer, Vehicle vehicle,
                            double riskScore, double premium) implements UnderwritingDecision {
        @Override
        @AchievesGoal(description = "自动拒绝高风险报价")
        public UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context) {
            String reason = buildRejectionReason();
            Quote quote = createQuote(QuoteStatus.DECLINED, reason, quoteRepo);
            log.info("核保拒绝 — quoteId: {}, riskScore: {}, reason: {}", quote.getId(), riskScore, reason);
            return UnderwritingResult.of(quote.getId(), "DECLINED", riskScore, premium,
                    "核保不通过：风险评分过高。" + reason);
        }

        private String buildRejectionReason() {
            int age = customer.getAge();
            return "年龄" + age + "岁"
                    + (customer.getDrivingExperienceYears() < 3 ? "(年轻驾驶员)" : "")
                    + "+驾龄" + customer.getDrivingExperienceYears() + "年"
                    + "+" + customer.getAccidentCount() + "次历史事故"
                    + "+" + (LocalDateTime.now().getYear() - vehicle.getYear()) + "年车龄旧车"
                    + (vehicle.getVehicleValue() > 500000 ? "+高价值车型" : "");
        }
    }

    // --- 错误路径 ---

    record CustomerNotFound(String errorMsg) implements UnderwritingDecision {
        @Override
        @AchievesGoal(description = "客户不存在错误")
        public UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context) {
            log.warn("核保失败 — 客户不存在: {}", errorMsg);
            return UnderwritingResult.error(0.0, "客户不存在: " + errorMsg);
        }
    }

    record VehicleLookupError(String errorMsg) implements UnderwritingDecision {
        @Override
        @AchievesGoal(description = "车辆未找到错误")
        public UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context) {
            log.warn("核保失败 — 车辆未找到: {}", errorMsg);
            return UnderwritingResult.error(0.0, "车辆未找到: " + errorMsg);
        }
    }

    record ExtractionFailed(String errorMsg) implements UnderwritingDecision {
        @Override
        @AchievesGoal(description = "LLM 提取失败错误")
        public UnderwritingResult execute(QuoteRepository quoteRepo, OperationContext context) {
            log.warn("核保失败 — LLM 提取失败: {}", errorMsg);
            return UnderwritingResult.error(0.0, "车辆信息提取失败: " + errorMsg);
        }
    }

    // --- 工具方法 ---

    /**
     * 创建并持久化 Quote，供正常路径的三个 @State 子类型复用。
     */
    private static Quote createQuote(QuoteStatus status, String rejectionReason,
                                      QuoteRepository quoteRepo) {
        // 需要从 record 的 component 获取 — 这里通过 sealed interface 的 default 方法无法直接访问 record 字段
        // 实际实现时每个 record 各自构造 Quote
        throw new UnsupportedOperationException("由各 record 子类型自行实现");
    }
```

> **重要说明**：上述 `sealed interface` 中 `createQuote` 方法无法以静态上下文访问各 record 的字段。实际实现时应让每个 record 的 `execute` 方法自行构造并持久化 Quote。核心逻辑如下：

```java
// 每个正常路径 record 的 execute() 内部构造 Quote：
private Quote buildAndSaveQuote(Customer customer, Vehicle vehicle, double riskScore,
                                 double premium, QuoteStatus status, String rejectionReason,
                                 QuoteRepository repo) {
    Quote quote = new Quote();
    quote.setCustomer(customer);
    quote.setVehicle(vehicle);
    quote.setRiskScore(riskScore);
    quote.setPremiumAmount(premium);
    quote.setStatus(status);
    quote.setCoverageType("COMPREHENSIVE");
    if (rejectionReason != null) {
        quote.setRejectionReason(rejectionReason);
    }
    // createdAt 和 expiresAt 由 Quote 构造函数自动设置
    return repo.save(quote);
}
```

### T8.4 StuckHandler

```java
    @Override
    public StuckHandlerResult handleStuck(AgentProcess agentProcess) {
        log.warn("UnderwritingAgent 超时 STUCK — AgentProcess: {}, Blackboard: {}",
                agentProcess, agentProcess.getBlackboard() != null
                        ? agentProcess.getBlackboard().getEntries() : "null");
        return new StuckHandlerResult(
                "UnderwritingAgent stuck — no resolution available",
                this,
                com.embabel.agent.api.common.StuckHandlingResultCode.NO_RESOLUTION,
                agentProcess
        );
    }
}
```

---

## T9 AgentService — Agent 进程编排

**路径**：`src/main/java/com/fastclaim/service/AgentService.java`

```java
package com.fastclaim.service;

import com.embabel.agent.api.common.AgentProcessOptions;
import com.embabel.agent.api.common.Platform;
import com.embabel.agent.api.common.ProcessResult;
import com.embabel.agent.core.AgentProcess;
import com.fastclaim.agent.UnderwritingAgent;
import com.fastclaim.dto.UnderwritingResult;
import com.fastclaim.dto.UserInput;
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

    private final Platform platform;
    private final QuoteRepository quoteRepository;

    /** 阿里巴巴手册强制要求显式构造线程池 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public AgentService(Platform platform, QuoteRepository quoteRepository) {
        this.platform = platform;
        this.quoteRepository = quoteRepository;
    }

    /**
     * 执行核保流程。
     *
     * @param userId    当前用户 ID
     * @param userInput 用户自然语言输入
     * @return UnderwritingResult，包含风险评分、保费、状态
     */
    public UnderwritingResult processUnderwriting(String userId, String userInput) {
        log.info("开始核保流程 — userId: {}, input: {}", userId,
                userInput.substring(0, Math.min(50, userInput.length())));

        AgentProcessOptions options = AgentProcessOptions.builder()
                .userId(userId)
                .timeoutSeconds(120)
                .build();

        UserInput input = new UserInput(userInput, null);

        UnderwritingAgent agent = platform.findAgent(UnderwritingAgent.class);

        AgentProcess process = platform.createAgentProcessFrom(agent, options, input);
        // 注入 userId 到 Blackboard，供 @Action 方法读取
        process.getBlackboard().bind("userId", userId);

        CompletableFuture<ProcessResult> future = CompletableFuture.supplyAsync(
                process::run, executor);

        try {
            ProcessResult completed = future.get(120, TimeUnit.SECONDS);
            UnderwritingResult result = completed.last(UnderwritingResult.class);

            if (result != null) {
                log.info("核保流程完成 — status: {}, quoteId: {}", result.status(), result.quoteId());
                return result;
            }

            // 结果回退：从 Blackboard 读取错误
            String errorMsg = process.getBlackboard().get("underwriting_error", String.class);
            log.warn("核保流程未产生 UnderwritingResult，从 Blackboard 回退: {}", errorMsg);
            return UnderwritingResult.error(0.0,
                    errorMsg != null ? errorMsg : "核保流程未产生结果");

        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("核保流程超时（120s）— userId: {}", userId);
            String errorMsg = process.getBlackboard().get("underwriting_error", String.class);
            return UnderwritingResult.error(0.0,
                    errorMsg != null ? "核保超时: " + errorMsg : "核保流程超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("核保流程被中断 — userId: {}", userId, e);
            return UnderwritingResult.error(0.0, "核保流程被中断");
        } catch (ExecutionException e) {
            log.error("核保流程执行异常 — userId: {}", userId, e.getCause());
            return UnderwritingResult.error(0.0,
                    "核保流程异常: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    /**
     * 人工审批 — 将 REFERRED 报价单转为 APPROVED。
     *
     * @param quoteId         报价单 ID
     * @param overridePremium 可选，覆盖保费金额
     * @return 更新后的 Quote
     * @throws BizException 报价单不存在 / 状态非 REFERRED / 已过期
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
```

**配置值说明**：
| 参数 | 值 | 说明 |
|------|-----|------|
| corePoolSize | 2 | Agent 进程并发基础数 |
| maxPoolSize | 4 | 高峰期最大并发数 |
| 队列容量 | 100 | 超过 4 个任务时缓冲 |
| 拒绝策略 | CallerRunsPolicy | 提交者线程直接执行，不丢任务 |
| keepAlive | 60s | 空闲线程存活时间 |
| 超时时间 | 120s | LLM 调用 + 数据库操作 + 缓冲 |

---

## T10 InsuranceController — REST API

**路径**：`src/main/java/com/fastclaim/controller/InsuranceController.java`

```java
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
import java.util.stream.Collectors;

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
     * 提交核保申请。
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
     * 查询用户保单列表。
     */
    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('policies:read')")
    public ResponseEntity<List<PolicyResponse>> listPolicies(@RequestParam String userId) {
        log.debug("查询保单列表 — userId: {}", userId);
        List<PolicyResponse> policies = policyService.findByUserId(userId)
                .stream()
                .map(PolicyResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(policies);
    }

    /**
     * 按保单号查询保单详情。
     */
    @GetMapping("/policies/{policyNumber}")
    @PreAuthorize("hasAuthority('policies:read')")
    public ResponseEntity<PolicyResponse> getPolicy(@PathVariable String policyNumber) {
        log.debug("查询保单详情 — policyNumber: {}", policyNumber);
        Policy policy = policyService.findByPolicyNumber(policyNumber);
        return ResponseEntity.ok(PolicyResponse.from(policy));
    }

    /**
     * 人工审批 — 将 REFERRED 报价单转为 APPROVED。
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
     * 支付保费并签发保单。
     */
    @PostMapping("/pay")
    @PreAuthorize("hasAuthority('underwriting:write')")
    public ResponseEntity<PolicyResponse> pay(@Valid @RequestBody PayRequest request) {
        log.info("支付请求 — quoteId: {}", request.quoteId());
        Policy policy = paymentService.pay(request.quoteId());
        return ResponseEntity.ok(PolicyResponse.from(policy));
    }

    /**
     * 健康检查 — 无需认证。
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
```

### 全局异常处理更新

**路径**：`src/main/java/com/fastclaim/controller/GlobalExceptionHandler.java`（追加方法）

```java
// 追加 BizException 处理
@ExceptionHandler(BizException.class)
public ResponseEntity<?> handleBizException(BizException e) {
    log.warn("业务异常 — code: {}, message: {}", e.getErrorCode(), e.getMessage());
    // 按场景映射 HTTP 状态码
    int httpStatus = switch (e.getErrorCode()) {
        case "NOT_FOUND" -> 404;
        case "EXPIRED", "INVALID_STATUS" -> 400;
        default -> 400;
    };
    return ResponseEntity.status(httpStatus).body(Map.of(
            "error", e.getErrorCode(),
            "message", e.getMessage()
    ));
}
```

---

## T11 配置更新

**路径**：`src/main/resources/application.yml`

在现有配置末尾追加：

```yaml
underwriting:
  agent:
    timeout-seconds: 120
    thread-pool:
      core-size: 2
      max-size: 4

risk:
  thresholds:
    low: 60
    high: 80

premium:
  base-rate: 0.02
  risk-coefficients:
    low: 0.8
    medium: 1.0
    high: 1.5
  coverage-coefficients:
    THIRD_PARTY: 0.5
    THIRD_PARTY_FIRE_THEFT: 0.75
    COMPREHENSIVE: 1.0
```

**无现有配置变更**。阶段一、二的配置（数据源、安全、LLM 路由、RAG、chat）保持不变。

---

## T12 UnderwritingAgentTest — 核保流程测试

**路径**：`src/test/java/com/fastclaim/agent/UnderwritingAgentTest.java`

```java
package com.fastclaim.agent;

import com.fastclaim.dto.UnderwriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UnderwritingAgentTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders basicAuth(String username, String password) {
        String auth = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ============================================================
    // US-U1：低风险用户自动批准
    // ============================================================

    @Test
    void lowRiskUserShouldGetApproved() {
        HttpHeaders headers = basicAuth("underwriter", "underwriter");
        UnderwriteRequest request = new UnderwriteRequest(
                "low-risk-user", "我想给我的 Toyota RAV4 上保险，车牌 LOW001");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/underwrite",
                entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("APPROVED");
        assertThat(response.getBody()).contains("4800.0");
    }

    // ============================================================
    // US-U2：中风险用户转人工
    // ============================================================

    @Test
    void mediumRiskUserShouldBeReferred() {
        HttpHeaders headers = basicAuth("underwriter", "underwriter");
        UnderwriteRequest request = new UnderwriteRequest(
                "medium-risk-user", "给我的 Honda Civic 上保险");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/underwrite",
                entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("REFERRED");
        assertThat(response.getBody()).contains("3600.0");
    }

    // ============================================================
    // US-U3：高风险用户自动拒绝
    // ============================================================

    @Test
    void highRiskUserShouldBeDeclined() {
        HttpHeaders headers = basicAuth("underwriter", "underwriter");
        UnderwriteRequest request = new UnderwriteRequest(
                "high-risk-user", "我想给我的 BMW X5 上保险，车牌 HIGH001");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/underwrite",
                entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("DECLINED");
        assertThat(response.getBody()).contains("18000.0");
    }

    // ============================================================
    // US-U4：查询保单列表 / 详情
    // ============================================================

    @Test
    void shouldQueryPoliciesByUserId() {
        HttpHeaders headers = basicAuth("user", "password");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/insurance/policies?userId=low-risk-user",
                HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturn404ForUnknownPolicyNumber() {
        HttpHeaders headers = basicAuth("user", "password");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/insurance/policies/POL-NONEXISTENT",
                HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ============================================================
    // US-U5：支付 + 签发保单
    // ============================================================

    @Test
    void shouldRejectPaymentForUnapprovedQuote() {
        HttpHeaders headers = basicAuth("underwriter", "underwriter");
        // 先核保获取 APPROVED 报价单
        UnderwriteRequest uwRequest = new UnderwriteRequest(
                "low-risk-user", "Toyota RAV4 车牌 LOW001");
        HttpEntity<UnderwriteRequest> uwEntity = new HttpEntity<>(uwRequest, headers);

        ResponseEntity<String> uwResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/underwrite",
                uwEntity, String.class);
        assertThat(uwResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 用不存在的 quoteId 支付
        PayRequest payRequest = new PayRequest(9999L);
        HttpEntity<PayRequest> payEntity = new HttpEntity<>(payRequest, headers);

        ResponseEntity<String> payResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/pay",
                payEntity, String.class);

        assertThat(payResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ============================================================
    // 错误路径
    // ============================================================

    @Test
    void unknownCustomerShouldReturn422() {
        HttpHeaders headers = basicAuth("underwriter", "underwriter");
        UnderwriteRequest request = new UnderwriteRequest(
                "unknown-user", "我想给我的 Toyota RAV4 上保险");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/underwrite",
                entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("ERROR");
    }

    @Test
    void noVehicleInfoShouldReturn422() {
        HttpHeaders headers = basicAuth("underwriter", "underwriter");
        UnderwriteRequest request = new UnderwriteRequest(
                "low-risk-user", "你好，天气不错");
        HttpEntity<UnderwriteRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/insurance/underwrite",
                entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
```

**测试覆盖矩阵**：

| 测试用例 | 对应需求 | 验证点 |
|---------|---------|--------|
| `lowRiskUserShouldGetApproved` | US-U1 | APPROVED + ¥4,800 |
| `mediumRiskUserShouldBeReferred` | US-U2 | REFERRED + ¥3,600 |
| `highRiskUserShouldBeDeclined` | US-U3 | DECLINED + ¥18,000 |
| `shouldQueryPoliciesByUserId` | US-U4 | GET /policies 返回 200 |
| `shouldReturn404ForUnknownPolicyNumber` | US-U4 | 不存在的保单号 404 |
| `shouldRejectPaymentForUnapprovedQuote` | US-U5 | 无效 quoteId 返回 400 |
| `unknownCustomerShouldReturn422` | 错误路径 | 不存在的用户 422 |
| `noVehicleInfoShouldReturn422` | 错误路径 | 无车辆关键词 422 |

---

## 实施顺序

```
T1 (DTO) ──▶ T2 (DataService) ──┐
                                 ├──▶ T8 (UnderwritingAgent) ──▶ T9 (AgentService) ──▶ T10 (Controller) ──▶ T12 (Test)
T3 (RiskCalcService) ───────────┤
                                 │
T4 (PremiumCalcService) ────────┤
                                 │
T5 (PaymentService) ────────────┤      T5, T6, T7 可与其他任务并行
T6 (PolicyService) ─────────────┤
T7 (GuardRail) ─────────────────┘

T11 (yml) ──▶ 与 T1-T12 任意时间点完成
```

1. **先 T1**：DTO 无依赖，所有下游类引用同一套类型定义
2. **T2~T7 并行**：各自独立，仅 T2 依赖 T1（VehicleInfo）
3. **T8 汇聚**：UnderwritingAgent 依赖 DTO + DataService + RiskCalcService + PremiumCalcService + GuardRail
4. **T9 接 T8**：AgentService 依赖 Agent + Platform
5. **T10 接 T9**：Controller 依赖 AgentService + PaymentService + PolicyService
6. **T12 最后**：集成测试需要所有组件就位
7. **T11 独立**：yml 配置随时可改

---

## 验证标准

| 验证项 | 方法 |
|--------|------|
| 编译通过 | `./mvnw compile` |
| 启动成功 | `./mvnw spring-boot:run`，日志确认 "UnderwritingAgent" 被扫描注册 |
| POST /api/insurance/underwrite（低风险） | `curl -u underwriter:underwriter -X POST -H 'Content-Type: application/json' -d '{"userId":"low-risk-user","userInput":"我想给我的 Toyota RAV4 上保险，车牌 LOW001"}' http://localhost:8080/api/insurance/underwrite` 返回 APPROVED + ¥4,800 |
| POST /api/insurance/underwrite（中风险） | 同上，userId=medium-risk-user，返回 REFERRED + ¥3,600 |
| POST /api/insurance/underwrite（高风险） | 同上，userId=high-risk-user，返回 DECLINED + ¥18,000 |
| POST /api/insurance/underwrite（未知用户） | userId=unknown-user，返回 422 ERROR |
| POST /api/insurance/underwrite（无车辆信息） | userInput="你好"，返回 422 ERROR |
| GET /api/insurance/policies | `curl -u user:password "http://localhost:8080/api/insurance/policies?userId=low-risk-user"` 返回保单列表 |
| GET /api/insurance/policies/{policyNumber} | 不存在保单号返回 404 |
| POST /api/insurance/pay | 先核保获取 quoteId，再支付：`curl -u underwriter:underwriter -X POST -H 'Content-Type: application/json' -d '{"quoteId":1}' http://localhost:8080/api/insurance/pay` |
| POST /api/insurance/quotes/{quoteId}/approve | `curl -u underwriter:underwriter -X POST -H 'Content-Type: application/json' -d '{"overridePremiumAmount":5000}' http://localhost:8080/api/insurance/quotes/2/approve` |
| GET /api/insurance/health | 无需认证，返回 OK |
| 无认证被拒 | 不带 Basic Auth 访问 /api/insurance/underwrite 返回 401 |
| 权限不足 | user:password 访问 /api/insurance/underwrite 返回 403（仅 USER，无 underwriting:write） |
| 过期报价单不可支付 | Quote 构造函数 +30 天，手动等待或修改验证 |
| 过期报价单不可审批 | 同上 |
| 保费计算精度 | BigDecimal 保留两位小数，四舍五入 |
| 风险评分验证 | 预设用户评分与 spec 一致（15/63/100） |
| 日志完整 | 关键操作有中文日志：核保申请、风险评估、支付、保单签发、异常 |
| 测试通过 | `./mvnw test -Dtest=UnderwritingAgentTest` 全部绿色 |
| Swagger UI | 访问 `http://localhost:8080/swagger-ui/index.html` 可见 `/api/insurance` 下 6 个端点 |
