# 阶段三概要设计：UnderwritingAgent — 智能核保 MVP

> 对应 `spec.md`，覆盖核保智能体、风险评分、保费计算、支付签单、Agent 编排、REST API。

---

## 1. 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                      Spring Boot 3.4.0                            │
│                                                                   │
│  ┌────────────────┐   ┌──────────────┐   ┌───────────────────┐   │
│  │InsuranceController│ │ AgentService │   │ UnderwritingAgent │   │
│  │ (REST API)     │──▶│ (编排+超时)   │──▶│ (Embabel @Agent)  │   │
│  └────────────────┘   └──────┬───────┘   └────────┬──────────┘   │
│                              │                     │               │
│              ┌───────────────┼─────────────────────┼───────┐      │
│              ▼               ▼                     ▼       ▼      │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐      │
│  │ DataService  │ │RiskCalcService│ │PremiumCalcService    │      │
│  │ 客户/车辆查询 │ │ 6因子风险评分 │ │ 保费计算              │      │
│  └──────┬───────┘ └──────────────┘ └──────────────────────┘      │
│         │                                                          │
│         ▼                                                          │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐      │
│  │PaymentService│ │PolicyService │ │VehicleInfoGuardRail   │      │
│  │ 模拟支付+签单│ │ 保单查询      │ │ LLM提取车辆信息护栏   │      │
│  └──────────────┘ └──────────────┘ └──────────────────────┘      │
│                                                                   │
│  ── 已有（阶段一+二） ────────────────────────────────────        │
│  6 JPA Entity + Repository │ SecurityConfig │ LlmSelectionService │
│  ChatbotAgent │ RAG (Lucene) │ ChatService │ ChatController       │
│  DataInitializer (种子数据已有 low/medium/high-risk-user)          │
└──────────────────────────────────────────────────────────────────┘
```

**新增分层职责**：
- **UnderwritingAgent** — Embabel UTILITY 规划器智能体，5 个 @Action 流水线 + 6 条 @State 路由
- **AgentService** — AgentProcess 编排，120s 超时保护，Blackboard 错误回退，人工审批
- **DataService** — 客户/车辆多维度查询，返回 sentinel 而非 null
- **RiskCalculationService** — 6 因子风险评分算法，结果钳制在 [0, 100]
- **PremiumCalculationService** — 保费 = 车价 × 2% × 风险系数 × 险种系数
- **PaymentService** — 模拟支付处理 + 自动签发保单
- **PolicyService** — 按 userId / policyNumber 查询保单列表
- **InsuranceController** — 6 个 REST 端点：核保申请、保单查询、支付签单、人工审批、健康检查
- **VehicleInfoGuardRail** — LLM 提取车辆信息护栏，确保输入包含可识别的车辆关键词

**与阶段一二的关系**：本阶段新增 UnderwritingAgent 及核保业务服务层，复用阶段一的 JPA Entity/Repository/安全认证体系和阶段二的 LLM 路由/RAG 基础设施。

---

## 2. UnderwritingAgent 设计

### 2.1 智能体定义

```java
@Agent(description = "核保 Agent", planner = PlannerType.UTILITY)
public class UnderwritingAgent implements StuckHandler {
    // 4 个 @Action + 6 条 @State 路由
}
```

- **规划器**：`UTILITY` — 按返回类型推导执行路径，保证流水线顺序
- **执行模型**：流水线（@Action 顺序执行）+ @State 分类路由
- **StuckHandler**：超时时打印 Blackboard 诊断信息，返回 `NO_RESOLUTION`

### 2.2 @Action 工作流

```
UserInput（自然语言："我想给我的 Toyota RAV4 上车险"）
  │
  ├─ [1] extractVehicleInfo(UserInput, OperationContext) → VehicleInfo
  │      构造 prompt 发送 LLM，从自然语言中提取结构化车辆信息（品牌/型号/车牌）
  │      使用 fast 模型（LlmSelectionService.forSimpleQuery()），降低延迟
  │      前置检查：先通过 userId 查 customer 是否存在，不存在则快速失败
  │
  ├─ [2] lookupCustomer(UserInput, OperationContext) → Customer
  │      从上下文获取 userId，调用 DataService.findCustomer(userId)
  │      未找到 → 返回 Customer.lookupFailed() sentinel
  │
  ├─ [3] lookupVehicle(VehicleInfo, Customer, OperationContext) → Vehicle
  │      调用 DataService.findVehicle(vehicleInfo, customer)
  │      查找优先级：车牌 → 车型 → 客户名下唯一车辆
  │      未找到 → 返回 Vehicle.lookupFailed() sentinel
  │
  ├─ [4] assessRisk(Customer, Vehicle, OperationContext) → UnderwritingDecision
  │      入口动作：先检查 Customer/Vehicle 是否为 sentinel
  │      → 是：路由到对应错误 @State 子类型
  │      → 否：RiskCalculationService.calculate(customer, vehicle) → 按阈值分类路由
  │
  └─ @State 分发（sealed interface UnderwritingDecision）:
       每个子类型包含 @AchievesGoal 方法，执行后 Agent 进程终止
```

### 2.3 @State 路由定义

```java
sealed interface UnderwritingDecision permits
    LowRiskQuote, MediumRiskReview, HighRiskDecline,
    CustomerNotFound, VehicleLookupError, ExtractionFailed {

    @AchievesGoal(description = "执行路由动作并返回核保结果")
    UnderwritingResult execute(OperationContext context);
}
```

| @State 子类型 | 触发条件 | execute() 行为 | 返回 status |
|---|---|---|---|
| LowRiskQuote | riskScore ≤ 60 | 生成 Quote(APPROVED)，持久化 | APPROVED |
| MediumRiskReview | 61 ≤ riskScore < 80 | 生成 Quote(REFERRED)，持久化 | REFERRED |
| HighRiskDecline | riskScore ≥ 80 | 生成 Quote(DECLINED)，持久化 | DECLINED |
| CustomerNotFound | Customer.isLookupFailed() | 不持久化，直接返回错误 | ERROR |
| VehicleLookupError | Vehicle.isLookupFailed() | 不持久化，直接返回错误 | ERROR |
| ExtractionFailed | LLM 提取 VehicleInfo 失败 | 不持久化，直接返回错误 | ERROR |

**设计要点**：
- Sentinel 检查在 assessRisk 入口完成，不在 DataService 中
- 错误路径不持久化 Quote，直接返回 UnderwritingResult 含错误信息
- 正常路径持久化 Quote 后返回，quoteId 由数据库自增生成

### 2.4 VehicleInfo DTO

```java
public record VehicleInfo(
    String brand,           // 品牌，如 "Toyota"
    String model,           // 型号，如 "RAV4"
    String licensePlate     // 车牌号，如 "LOW001"，可为 null
) {
    public static final VehicleInfo EXTRACTION_FAILED = new VehicleInfo("", "", null);

    public boolean isFailed() {
        return brand.isEmpty() && model.isEmpty();
    }
}
```

### 2.5 UnderwritingResult DTO

```java
public record UnderwritingResult(
    Long quoteId,
    String status,          // APPROVED / REFERRED / DECLINED / ERROR
    double riskScore,
    double premiumAmount,
    String message
) {}
```

### 2.6 LLM 提取策略

`extractVehicleInfo` 通过 prompt 工程从自然语言中提取车辆信息：

```
System: 你是一个车辆信息提取工具。从用户输入中提取车辆的品牌、型号和车牌号。
        以 JSON 格式返回：{"brand":"...","model":"...","licensePlate":"..."}
        如果无法识别任何车辆信息，返回：{"brand":"","model":"","licensePlate":null}
User: 用户输入：{userInput}
```

- 使用 fast 模型（deepseek-chat），减少延迟和成本
- 前置检查 customer 存在性，不存在则跳过 LLM 调用直接返回 EXTRACTION_FAILED

---

## 3. DataService 设计

### 3.1 接口定义

```java
@Service
public class DataService {
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;
}
```

### 3.2 方法签名

| 方法 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `findCustomer(userId)` | String userId | Customer | 按 userId 查找，未找到返回 `Customer.lookupFailed()` sentinel |
| `findVehicle(vehicleInfo, customer)` | VehicleInfo, Customer | Vehicle | 优先级：车牌精确匹配 → 品牌型号匹配 → 客户名下唯一车辆，均未找到返回 `Vehicle.lookupFailed()` sentinel |

### 3.3 车辆查找逻辑

```
findVehicle(VehicleInfo info, Customer customer):
  1. if info.licensePlate() != null:
       vehicle = vehicleRepository.findByLicensePlate(info.licensePlate())
       if found && vehicle.customer == customer → return vehicle
  2. vehicles = vehicleRepository.findByCustomerId(customer.getId())
  3. if info.brand() 和 info.model() 均非空:
       match = vehicles 中 brand + model 均匹配的车辆
       if found → return match
  4. if vehicles.size() == 1 → return vehicles.get(0)  // 客户名下唯一车辆
  5. return Vehicle.lookupFailed()
```

---

## 4. RiskCalculationService 设计

### 4.1 接口定义

```java
@Service
public class RiskCalculationService {
    // 6 因子评分常量
    private static final double LOW_RISK_THRESHOLD = 60.0;
    private static final double HIGH_RISK_THRESHOLD = 80.0;

    public double calculate(Customer customer, Vehicle vehicle);
}
```

### 4.2 评分因子表

| 因子 | 条件 | 加分 | 说明 |
|------|------|------|------|
| 年龄 | < 25 岁 | +25 | `customer.getAge()` 动态计算 |
| 年龄 | 25–35 岁 | +15 | |
| 年龄 | 36–64 岁 | 0（基准） | |
| 年龄 | ≥ 65 岁 | +20 | |
| 驾龄 | < 3 年 | +20 | `customer.getDrivingExperienceYears()` |
| 驾龄 | 3–5 年 | +10 | |
| 驾龄 | 6–19 年 | 0（基准） | |
| 驾龄 | ≥ 20 年 | −10 | 负分扣减 |
| 事故次数 | 每次 | +15 | `customer.getAccidentCount() × 15` |
| 车龄 | > 10 年 | +15 | `当前年 − vehicle.getYear()` |
| 车龄 | 6–10 年 | +8 | |
| 车辆价值 | > ¥500,000 | +10 | `vehicle.getVehicleValue()` |

- 最终分数钳制在 [0, 100]
- 阈值：≤ 60 APPROVED · 61–79 REFERRED · ≥ 80 DECLINED

### 4.3 预设用户评分验证

| 用户 | 年龄(分) | 驾龄(分) | 事故(分) | 车龄(分) | 车价(分) | 总分 | 结果 |
|------|---------|---------|---------|---------|---------|------|------|
| low-risk-user | 0 | 0 | 15 | 0 | 0 | 15 | APPROVED |
| medium-risk-user | 15 | 10 | 30 | 8 | 0 | 63 | REFERRED |
| high-risk-user | 25 | 20 | 45 | 15 | 10 | 115→100 | DECLINED |

---

## 5. PremiumCalculationService 设计

### 5.1 接口定义

```java
@Service
public class PremiumCalculationService {

    public double calculate(double vehicleValue, double riskScore, String coverageType);
}
```

### 5.2 计算公式

```
保费 = 车辆价值 × 2% × 风险系数 × 险种系数
```

| 参数 | 取值 | 系数 |
|------|------|------|
| 风险系数 | riskScore < 40 | ×0.8 |
| | 40 ≤ riskScore < 70 | ×1.0 |
| | riskScore ≥ 70 | ×1.5 |
| 险种系数 | THIRD_PARTY（第三者责任险） | ×0.5 |
| | THIRD_PARTY_FIRE_THEFT（盗抢险） | ×0.75 |
| | COMPREHENSIVE（综合险） | ×1.0 |

### 5.3 计算示例

| 车型 | 车价 | 风险评分 | 险种 | 计算 | 保费 |
|------|------|---------|------|------|------|
| Toyota RAV4 | ¥300k | 15 | COMPREHENSIVE | 300k×2%×0.8×1.0 | ¥4,800 |
| Honda Civic | ¥180k | 63 | COMPREHENSIVE | 180k×2%×1.0×1.0 | ¥3,600 |
| BMW X5 | ¥600k | 100 | COMPREHENSIVE | 600k×2%×1.5×1.0 | ¥18,000 |

---

## 6. PaymentService 设计

### 6.1 接口定义

```java
@Service
public class PaymentService {
    private final QuoteRepository quoteRepository;
    private final PolicyRepository policyRepository;

    public Policy pay(Long quoteId);
}
```

### 6.2 支付流程

```
pay(quoteId):
  1. quote = quoteRepository.findById(quoteId)
     → 未找到：抛出 BizException("报价单不存在")
  2. 状态校验：quote.status == APPROVED
     → 非 APPROVED：抛出 BizException("仅已批准的报价单可支付")
  3. 过期校验：quote.expiresAt.isAfter(LocalDateTime.now())
     → 已过期：抛出 BizException("报价单已过期")
  4. 模拟支付处理（log.info 记录支付成功）
  5. 创建 Policy：
     - policyNumber: 构造函数自动生成 POL-{timestamp}-{6位随机大写}
     - customer: quote.customer
     - vehicle: quote.vehicle
     - coverageType: quote.coverageType
     - premiumAmount: quote.premiumAmount
     - effectiveDate: LocalDateTime.now()
     - expirationDate: effectiveDate.plusDays(365)
     - status: ACTIVE
  6. policyRepository.save(policy)
  7. return policy
```

### 6.3 业务规则

- 仅 APPROVED 状态可支付
- 过期报价单（30 天）不可支付
- 保单有效期默认一年（使用 `LocalDate.now().lengthOfYear()` 获取当年天数，避免硬编码 365）

---

## 7. PolicyService 设计

### 7.1 接口定义

```java
@Service
public class PolicyService {
    private final PolicyRepository policyRepository;

    public List<Policy> findByUserId(String userId);
    public Policy findByPolicyNumber(String policyNumber);
}
```

### 7.2 方法说明

| 方法 | 说明 |
|------|------|
| `findByUserId(userId)` | 返回用户的所有保单列表；未找到返回空列表（非 null） |
| `findByPolicyNumber(policyNumber)` | 精确匹配保单号；未找到抛出 BizException |

---

## 8. AgentService 编排

### 8.1 接口定义

```java
@Service
public class AgentService {
    private final AgentPlatform agentPlatform;
    private final QuoteRepository quoteRepository;

    public UnderwritingResult processUnderwriting(String userId, String userInput);
    public Quote approveQuote(Long quoteId, Double overridePremium);
}
```

### 8.2 processUnderwriting 流程

```
processUnderwriting(userId, userInput):
  1. agent = agentPlatform.findAgent(UnderwritingAgent.class)
  2. options = AgentProcessOptions.builder()
         .userId(userId)
         .timeoutSeconds(120)
         .build()
  3. process = agentPlatform.createAgentProcessFrom(agent, options, userInput)
  4. future = CompletableFuture.supplyAsync(process::run)
  5. completed = future.get(120, TimeUnit.SECONDS)
         ├─ 正常完成 → completed.last(UnderwritingResult.class)
         └─ 超时 → future.cancel(true) → 从 Blackboard 回退读取错误 → 包装为 ERROR 响应
  6. if result == null → blackboard.get("underwriting_error") → 包装为 ERROR
  7. return result
```

### 8.3 关键特性

| 特性 | 值 | 说明 |
|------|-----|------|
| 超时时间 | 120 秒 | 覆盖 LLM 调用 + 数据库操作 |
| StuckHandler | 打印 Blackboard 诊断 + NO_RESOLUTION | UnderwritingAgent 实现 |
| 结果回退 | Blackboard["underwriting_error"] | @State 错误路由结果从此读取 |
| 线程池 | `new ThreadPoolExecutor(2, 4, 60L, SECONDS, LinkedBlockingQueue(100))` | 阿里巴巴手册要求显式构造 |

### 8.4 approveQuote 审批流程

```
approveQuote(quoteId, overridePremium):
  1. quote = quoteRepository.findById(quoteId)
  2. 校验：status == REFERRED && expiresAt > now
  3. if overridePremium != null → quote.premiumAmount = overridePremium
  4. quote.status = APPROVED
  5. quoteRepository.save(quote)
  6. return quote
```

---

## 9. REST API 设计

### 9.1 InsuranceController — `/api/insurance`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/underwrite` | `hasAuthority('underwriting:write')` | 提交核保申请 |
| GET | `/policies` | `hasAuthority('policies:read')` | 查询用户保单列表 |
| GET | `/policies/{policyNumber}` | `hasAuthority('policies:read')` | 查询单个保单详情 |
| POST | `/quotes/{quoteId}/approve` | `hasAuthority('underwriting:approve')` | 人工审批 REFERRED 报价单 |
| POST | `/pay` | `hasAuthority('underwriting:write')` | 支付保费，签发保单 |
| GET | `/health` | `permitAll` | 健康检查 |

### 9.2 请求/响应 DTO

| DTO | 类型 | 核心字段 |
|-----|------|---------|
| `UnderwriteRequest` | record | `String userId, String userInput` |
| `UnderwriteResponse` | record | `Long quoteId, String status, double riskScore, double premiumAmount, String message`（映射自 UnderwritingResult） |
| `PayRequest` | record | `Long quoteId` |
| `PolicyResponse` | record | `String policyNumber, String customerName, String vehicleModel, String coverageType, double premiumAmount, LocalDateTime effectiveDate, LocalDateTime expirationDate, String status` |
| `ApproveQuoteRequest` | record | `Double overridePremiumAmount` |
| `ApproveQuoteResponse` | record | `Long quoteId, String status, double premiumAmount, String message` |

### 9.3 端点详情

**POST /api/insurance/underwrite**：
- 请求体：`UnderwriteRequest`
- 成功响应（200）：`UnderwriteResponse`
- 错误响应（422）：Customer not found / Vehicle extraction failed 等

**GET /api/insurance/policies**：
- 查询参数：`userId`
- 成功响应（200）：`List<PolicyResponse>`

**GET /api/insurance/policies/{policyNumber}**：
- 成功响应（200）：`PolicyResponse`
- 错误响应（404）：保单不存在

**POST /api/insurance/quotes/{quoteId}/approve**：
- 请求体：`ApproveQuoteRequest`（overridePremiumAmount 可选）
- 成功响应（200）：`ApproveQuoteResponse`
- 错误响应（400）：非 REFERRED 状态 / 已过期

**POST /api/insurance/pay**：
- 请求体：`PayRequest`
- 成功响应（200）：`PolicyResponse`
- 错误响应（400）：非 APPROVED 状态 / 已过期

### 9.4 错误码汇总

| 状态码 | 场景 |
|--------|------|
| 400 | 请求参数校验失败 / 报价单状态不符 / 报价单已过期 |
| 401 | 未认证 |
| 403 | 无对应权限 |
| 404 | 保单/报价单不存在 |
| 422 | 核保申请处理失败（客户不存在、车辆未找到、LLM 提取失败） |

---

## 10. Guardrail 设计

### 10.1 VehicleInfoGuardRail

实现 Embabel 的 `UserInputGuardRail` 接口，挂载于 UnderwritingAgent 的 `extractVehicleInfo` @Action：

```java
public class VehicleInfoGuardRailImpl implements UserInputGuardRail {
    // 检查输入是否包含至少一个可识别的车辆关键词
    // 关键词正则：品牌词库 + 车牌格式 + 车型关键词
}
```

| 检测类型 | 规则 | 命中返回 |
|---------|------|---------|
| 无车辆关键词 | 输入不含品牌（Toyota/Honda/BMW/Tesla 等）或车牌格式（`[A-Z]{2,3}\d{3,5}`） | 422 |
| 仅模糊表述 | "我的车"、"一辆车" 等无具体品牌/型号 | 422 |

**挂载方式**：在 `extractVehicleInfo` @Action 上通过 Embabel 的 `@GuardRail(VehicleInfoGuardRailImpl.class)` 注解声明。

---

## 11. 配置变更

### 11.1 application.yml 新增段

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
  base-rate: 0.02              # 2% 基础费率
  risk-coefficients:
    low: 0.8                   # riskScore < 40
    medium: 1.0                # 40 ≤ riskScore < 70
    high: 1.5                  # riskScore ≥ 70
  coverage-coefficients:
    THIRD_PARTY: 0.5
    THIRD_PARTY_FIRE_THEFT: 0.75
    COMPREHENSIVE: 1.0
```

### 11.2 无需修改现有配置

阶段一、二的配置（数据源、安全、LLM 路由、RAG、chat）保持不变，本阶段仅新增段。

---

## 12. 依赖变更

**无新增 Maven 依赖**。本阶段完全复用阶段一、二的依赖栈：
- Embabel Agent 框架（@Agent、@Action、@State、PlannerType.UTILITY）
- Spring Data JPA（Repository 已就绪）
- Spring Security（角色权限已配置）
- DeepSeek LLM（LlmSelectionService 已提供 `forSimpleQuery()` → fast）

---

## 13. 实施清单

| 序号 | 任务 | 产出 | 依赖 |
|------|------|------|------|
| 1 | 创建 DTO 类（VehicleInfo、UnderwritingResult、UnderwriteRequest、UnderwriteResponse、PayRequest、PolicyResponse、ApproveQuoteRequest、ApproveQuoteResponse） | `dto/` | — |
| 2 | 实现 DataService（客户/车辆多维度查询 + sentinel 返回） | `service/DataService.java` | T1（VehicleInfo） |
| 3 | 实现 RiskCalculationService（6 因子评分 + 阈值钳制） | `service/RiskCalculationService.java` | — |
| 4 | 实现 PremiumCalculationService（保费计算公式） | `service/PremiumCalculationService.java` | — |
| 5 | 实现 PaymentService（模拟支付 + 签发保单） | `service/PaymentService.java` | T1 |
| 6 | 实现 PolicyService（保单查询） | `service/PolicyService.java` | — |
| 7 | 实现 VehicleInfoGuardRail（车辆关键词护栏） | `guardrail/VehicleInfoGuardRailImpl.java` | — |
| 8 | 实现 UnderwritingAgent（5 @Action + sealed interface + 6 @State 路由） | `agent/UnderwritingAgent.java` | T1, T2, T3, T4, T7 |
| 9 | 实现 AgentService（AgentProcess 编排 + 120s 超时 + Blackboard 回退 + approveQuote） | `service/AgentService.java` | T8 |
| 10 | 实现 InsuranceController（6 端点 + 参数校验 + 权限控制） | `controller/InsuranceController.java` | T1, T9 |
| 11 | 更新 application.yml（underwriting.* / risk.* / premium.* 段） | `resources/application.yml` | — |
| 12 | 编写 UnderwritingAgentTest（验证 low/medium/high 三种路径） | `agent/UnderwritingAgentTest.java` | T8, T9, T10 |

**建议实施顺序**：T1 → (T2~T7 并行) → T8 → T9 → T10 → T11 → T12

---

## 14. 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| Agent 规划器 | UTILITY | 按返回类型推导执行路径，保证 4 步流水线严格顺序 |
| Sentinel 模式 | Customer.lookupFailed() / Vehicle.lookupFailed()（已有） | 避免 null 导致 UTILITY 规划器 STUCK；判定逻辑集中在 assessRisk 入口 |
| 错误传播 | Blackboard 写入，不抛异常 | 框架会吞掉并重试异常，Blackboard 保证错误可达 |
| 快速失败 | extractVehicleInfo 前先查 customer | 避免 customer 不存在时白白调用 LLM（120s 超时） |
| 风险评分钳制 | [0, 100] | 得分上限 100，避免评分溢出无上限 |
| 保费计算 | 公式化计算，非 LLM | 保费涉及金额必须精确可审计，LLM 不适合做数值计算 |
| 支付模拟 | log.info 记录，不接真实支付 | MVP 阶段快速验证流程，预留 PaymentGateway 接口替换点 |
| 险种类型 | String 存储（与 Quote 已有字段一致） | MVP 阶段简单可靠，后续可改为枚举 |
| 线程池 | 显式 ThreadPoolExecutor | 阿里巴巴手册强制要求，禁止使用 Executors 创建 |
| 超时时间 | 120 秒 | 覆盖 LLM 提取（~10s）+ 数据库操作（~1s）+ 缓冲 |
| 保单有效期 | `LocalDate.now().lengthOfYear()` | 阿里巴巴手册禁止硬编码 365 天 |
| 审批保费覆盖 | 可选参数，不传则保持原保费 | 核保员可根据实际情况调整保费后批准 |
| 无新依赖 | 零新增 Maven 依赖 | 全部复用阶段一、二的 Embabel + Spring Data JPA 技术栈 |
