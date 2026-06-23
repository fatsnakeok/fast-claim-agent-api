# 阶段三需求规格：UnderwritingAgent — 智能核保 MVP

> 以 UnderwritingAgent 为核心交付车险智能核保能力。
> 前置依赖：阶段一（数据层） + 阶段二（RAG 基础设施，固化为平台能力）。
> 逆向工程来源：`doc/业务分析.md` §2.1–§2.3 + `doc/技术分析.md` §3.1。

---

## S3.1 MVP 概述

### S3.1.1 本阶段目标

交付**车险智能核保**功能。用户以自然语言提交投保申请，系统通过 LLM 提取车辆信息、查询客户/车辆档案、执行风险评分，自动将投保申请路由到**批准 / 转人工 / 拒绝**三条路径。

### S3.1.2 交付物

| 模块 | 内容 |
|------|------|
| **UnderwritingAgent** | 5 个 @Action + 6 条 @State 路由的核保智能体 |
| **DataService** | 客户/车辆多维度查询服务 |
| **RiskCalculationService** | 6 因子风险评分算法 |
| **PremiumCalculationService** | 保费 = 车价 × 2% × 风险系数 × 险种系数 |
| **AgentService** | AgentProcess 编排：创建、运行、超时保护、错误回退 |
| **PaymentService** | 模拟支付 + 自动签发保单 |
| **PolicyService** | 按 userId / policyNumber 查询保单 |
| **核保 API** | 核保申请、保单查询、支付签单、人工审批 |

### S3.1.3 用户故事

| 编号 | 故事 | 验收标准 |
|------|------|---------|
| US-U1 | 作为低风险用户，我想提交投保申请并获得自动批准 | 输入 "我想给我的 Toyota RAV4 上保险"，返回 APPROVED + 报价单 |
| US-U2 | 作为中风险用户，我的投保申请被转给核保员审核 | 输入投保申请后返回 REFERRED，核保员审批通过后才能支付 |
| US-U3 | 作为高风险用户，我的投保申请被自动拒绝并告知原因 | 返回 DECLINED + 拒绝原因 |
| US-U4 | 作为用户，我想查看我的所有保单 | GET /api/insurance/policies 返回保单列表 |
| US-U5 | 作为用户，我对已批准的报价单进行支付并获得正式保单 | POST /api/insurance/pay 后返回保单号 |

---

## S3.2 UnderwritingAgent 设计

### S3.2.1 智能体定义

```
@Agent(description = "核保 Agent", planner = PlannerType.UTILITY)
UnderwritingAgent implements StuckHandler
```

- **规划器类型**：`UTILITY` — 按返回类型严格推导执行路径，确保流水线顺序
- **执行模型**：流水线 (@Action 顺序执行) + @State 分类路由

### S3.2.2 @Action 工作流

```
用户输入 (自然语言："我想给我的 Toyota RAV4 上车险")
  │
  ├─ [1] extractVehicleInfo(UserInput, OperationContext) → VehicleInfo
  │      LLM 从自然语言中提取结构化车辆信息（品牌/型号/车牌）
  │      挂载 VehicleInfoGuardRail：输入必须包含可识别的车辆关键词
  │      失败诊断：先查 customer 是否存在，避免无效 LLM 调用
  │
  ├─ [2] lookupCustomer(UserInput, OperationContext) → Customer
  │      从 DB 按 userId 查找客户 → 未找到返回 sentinel (非 null)
  │
  ├─ [3] lookupVehicle(VehicleInfo, Customer, OperationContext) → Vehicle
  │      查找优先级：车牌 → 车型 → 客户名下唯一车辆
  │      未找到返回 sentinel
  │
  ├─ [4] assessRisk(Customer, Vehicle, OperationContext) → UnderwritingDecision
  │      入口动作：先检查前置错误(Customer/Vehicle sentinel)
  │      → RiskCalculationService 计算风险评分
  │      → 按阈值分类，返回 @State 子类型
  │
  └─ @State 分发（sealed interface UnderwritingDecision）:
```

### S3.2.3 @State 路由定义

```
sealed interface UnderwritingDecision {
    ┌─────────────────────────────────────────────────────────┐
    │ 正常路径                                                   │
    ├───────────────────┬─────────────────┬───────────────────┤
    │ LowRiskQuote      │ MediumRiskReview│ HighRiskDecline   │
    │ riskScore ≤ 60    │ 61 ≤ score < 80 │ riskScore ≥ 80    │
    │ → 自动批准         │ → 转人工审核     │ → 自动拒绝         │
    │ → 生成 APPROVED   │ → 生成 REFERRED │ → 生成 DECLINED   │
    │   报价单           │   报价单         │   报价单           │
    ├───────────────────┼─────────────────┼───────────────────┤
    │ 错误路径                                                   │
    ├───────────────────┼─────────────────┼───────────────────┤
    │ CustomerNotFound  │ VehicleLookup   │ ExtractionFailed  │
    │ 客户未找到         │ Error           │ LLM 提取失败       │
    │ → 返回 ERROR      │ 车辆未找到       │ → 返回 ERROR      │
    │                   │ → 返回 ERROR    │                   │
    └───────────────────┴─────────────────┴───────────────────┘
}
```

每个 @State 子类型包含一个 `@AchievesGoal` 方法，执行后 Agent 进程终止。

### S3.2.4 UnderwritingResult 输出

```java
public record UnderwritingResult(
    Long quoteId,          // 报价单 ID
    String status,         // APPROVED / REFERRED / DECLINED / ERROR
    double riskScore,      // 风险评分 [0, 100]
    double premiumAmount,  // 保费金额
    String message         // 人类可读的结果说明
) {}
```

### S3.2.5 关键设计决策

| 决策 | 说明 |
|------|------|
| **Sentinel 模式** | 查不到 Customer/Vehicle 时返回 `lookupFailed()` 占位对象而非 null，避免 UTILITY 规划器因类型缺失 STUCK |
| **Blackboard 错误传播** | 不抛异常（会被框架重试吞掉），而是写入 `Blackboard["underwriting_error"]`，通过 @State 错误路由返回 |
| **快速失败** | `extractVehicleInfo` 先查 customer 是否存在，不存在则拒绝 LLM 调用，避免白白等待 120s 超时 |

---

## S3.3 风险评分算法

### S3.3.1 RiskCalculationService

6 因子评分模型，最终钳制在 [0, 100]：

| 因子 | 条件 | 加分 |
|------|------|------|
| 年龄 | < 25 岁 | +25 |
| 年龄 | 25–35 岁 | +15 |
| 年龄 | 36–64 岁 | 0（基准） |
| 年龄 | ≥ 65 岁 | +20 |
| 驾龄 | < 3 年 | +20 |
| 驾龄 | 3–5 年 | +10 |
| 驾龄 | 6–19 年 | 0（基准） |
| 驾龄 | ≥ 20 年 | −10 |
| 事故次数 | 每次 | +15 |
| 车龄 | > 10 年 | +15 |
| 车龄 | 6–10 年 | +8 |
| 车辆价值 | > ¥500,000 | +10 |

**阈值**：≤ 60 自动批准 · 61–79 转人工 · ≥ 80 拒绝

### S3.3.2 预设用户评分示例

| 用户 | 年龄(分) | 驾龄(分) | 事故(分) | 车龄(分) | 车价(分) | 总分 | 结果 |
|------|---------|---------|---------|---------|---------|------|------|
| low-risk-user | 0 | 0 | 15 | 0 | 0 | 15 | APPROVED |
| medium-risk-user | 15 | 10 | 30 | 8 | 0 | 63 | REFERRED |
| high-risk-user | 25 | 20 | 45 | 15 | 10 | 115→100 | DECLINED |

---

## S3.4 保费计算

### S3.4.1 PremiumCalculationService

```
保费 = 车辆价值 × 2% × 风险系数 × 险种系数
```

| 参数 | 取值 | 系数 |
|------|------|------|
| 风险系数 | riskScore < 40 | ×0.8 |
| | 40 ≤ riskScore < 70 | ×1.0 |
| | riskScore ≥ 70 | ×1.5 |
| 险种系数 | 第三者责任险 (THIRD_PARTY) | ×0.5 |
| | 盗抢险 (THIRD_PARTY_FIRE_THEFT) | ×0.75 |
| | 综合险 (COMPREHENSIVE) | ×1.0 |

### S3.4.2 计算示例

| 车型 | 车价 | 风险评分 | 险种 | 保费计算 | 保费 |
|------|------|---------|------|---------|------|
| Toyota RAV4 | ¥300k | 15 | COMPREHENSIVE | 300k×2%×0.8×1.0 | ¥4,800 |
| Honda Civic | ¥180k | 63 | COMPREHENSIVE | 180k×2%×1.0×1.0 | ¥3,600 |
| BMW X5 | ¥600k | 100 | COMPREHENSIVE | 600k×2%×1.5×1.0 | ¥18,000 |

---

## S3.5 支付与签单

### S3.5.1 PaymentService

```
process:
  1. 查询报价单：quoteRepository.findById(quoteId)
  2. 状态校验：status == APPROVED && 未过期(expiresAt > now)
  3. 模拟支付处理
  4. 生成保单号：POL-{timestamp}-{6位随机大写}
  5. 创建保单：customer + vehicle + premiumAmount + coverageType
     effectiveDate = now, expirationDate = now + 365天, status = ACTIVE
  6. 持久化并返回 Policy
```

**业务规则**：
- 仅 APPROVED 状态可支付
- 过期报价单（30 天）不可支付
- 保单有效期默认一年

---

## S3.6 人工审批

### S3.6.1 审批流程

```
核保员对 REFERRED 报价单发起审批
  │
  ├─ 校验：quote.status == REFERRED && quote.expiresAt > now
  ├─ 可选：覆盖保费金额
  ├─ 状态变更：REFERRED → APPROVED
  │
  └─ 返回 ApproveQuoteResponse
```

---

## S3.7 AgentService 编排

### S3.7.1 核心流程

```
AgentService.processUnderwriting(userId, userInput):
  1. 查找 UnderwritingAgent
  2. AgentPlatform.createAgentProcessFrom(agent, options, userInput)
  3. CompletableFuture.supplyAsync(process::run)
     .get(120, TimeUnit.SECONDS)           ← 超时保护
  4. completed.last(UnderwritingResult)    ← 从 process 提取结果
  5. 若为 null → 从 Blackboard 回退读取错误 → 包装为 ERROR 响应
```

### S3.7.2 关键特性

| 特性 | 值 |
|------|-----|
| 超时时间 | 120 秒 |
| StuckHandler | 三个 Agent 均实现，超时时打印 Blackboard 诊断 |
| 注入检测 | `containsUnauthorizedCommand()` 正则拦截 `ignore all rules` 等 |
| 结果回退 | @State 错误路由结果从 Blackboard 读取 |

---

## S3.8 REST API

### S3.8.1 InsuranceController — `/api/insurance`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/underwrite` | `underwriting:write` | 提交核保申请 |
| GET | `/policies` | `policies:read` | 查询用户保单列表 |
| GET | `/policies/{policyNumber}` | `policies:read` | 查询单个保单详情 |
| POST | `/quotes/{quoteId}/approve` | `underwriting:approve` | 人工审批 REFERRED 报价单 |
| POST | `/pay` | `underwriting:write` | 支付保费，签发保单 |
| GET | `/health` | — | 健康检查 |

### S3.8.2 POST /api/insurance/underwrite

**请求**（HTTP Basic Auth：low-risk-user / password）：
```json
{
  "userId": "low-risk-user",
  "userInput": "我想给我的 Toyota RAV4 上保险，车牌 LOW001"
}
```

**响应（低风险）**：
```json
{
  "quoteId": 1,
  "status": "APPROVED",
  "riskScore": 15.0,
  "premiumAmount": 4800.0,
  "message": "核保通过。保费 ¥4,800.00，报价单有效期 30 天。"
}
```

**响应（中风险）**：
```json
{
  "quoteId": 2,
  "status": "REFERRED",
  "riskScore": 63.0,
  "premiumAmount": 3600.0,
  "message": "需要人工审核。您的申请已转交核保员处理。"
}
```

**响应（高风险）**：
```json
{
  "quoteId": 3,
  "status": "DECLINED",
  "riskScore": 100.0,
  "premiumAmount": 18000.0,
  "message": "核保不通过：风险评分过高。原因：年龄21岁(年轻驾驶员)+驾龄仅1年+3次历史事故+13年车龄旧车+高价值车型。"
}
```

**响应（错误）**：
```
HTTP 422: "Customer not found: unknown-user"
```

---

## S3.9 需求追溯矩阵

| 需求编号 | 需求项 | 对应源码 |
|---------|-------|---------|
| S3.2.2 | extractVehicleInfo @Action | `agent/UnderwritingAgent.java` — `extractVehicleInfo()` |
| S3.2.2 | lookupCustomer @Action | `agent/UnderwritingAgent.java` — `lookupCustomer()` |
| S3.2.2 | lookupVehicle @Action | `agent/UnderwritingAgent.java` — `lookupVehicle()` |
| S3.2.2 | assessRisk @Action | `agent/UnderwritingAgent.java` — `assessRisk()` |
| S3.2.3 | 6 条 @State 路由 | `agent/UnderwritingAgent.java` — sealed interface + 6 records |
| S3.3 | 风险评分 | `service/RiskCalculationService.java` |
| S3.4 | 保费计算 | `service/PremiumCalculationService.java` |
| S3.5 | 支付签单 | `service/PaymentService.java` |
| S3.6 | 人工审批 | `service/AgentService.java` — `approveQuote()` |
| S3.7 | Agent 编排 | `service/AgentService.java` — `processUnderwriting()` |
| S3.8 | REST API | `controller/InsuranceController.java` |
| — | 数据查询 | `service/DataService.java` |
| — | 保单查询 | `service/PolicyService.java` |

---

## S3.10 阶段三与前序阶段的关系

```
阶段一 + 阶段二 (spec1 + spec2)
  ├─ 6 个 JPA Entity + Repository + DataInitializer
  ├─ SecurityConfig (4 角色 HTTP Basic Auth)
  ├─ LlmSelectionService
  ├─ ChatbotAgent + RAG (Lucene + ToolishRag)
  ├─ ChatService + ChatController
  └─ Guardrail + Cache
       │
       ▼
阶段三 (spec3.md) ← 本阶段
  ├─ UnderwritingAgent (5 @Action + 6 @State 路由)
  ├─ RiskCalculationService (6 因子评分)
  ├─ PremiumCalculationService (保费计算)
  ├─ DataService (客户/车辆查询)
  ├─ PaymentService (支付 + 签发保单)
  ├─ PolicyService (保单查询)
  ├─ AgentService (AgentProcess 编排 + 120s 超时 + Blackboard 回退)
  ├─ InsuranceController (核保/保单/支付/审批 6 端点)
  └─ VehicleInfo DTO + VehicleInfoGuardRail
       │
       ▼
阶段四 (spec4.md)
  └─ ClaimsAgent ...
```

**本阶段新增依赖**：无新增外部依赖，复用阶段一、二的栈。
