# 阶段四需求规格：ClaimsAgent — 智能理赔 MVP

> 以 ClaimsAgent 为核心交付车险智能理赔能力，至此三个 Agent 全部就位，形成完整的智能保险平台。
> 前置依赖：阶段一（数据层）+ 阶段二（RAG 基础设施）+ 阶段三（核保 + 保单）。


---

## S4.1 MVP 概述

### S4.1.1 本阶段目标

交付**车险智能理赔**功能。用户提交理赔申请（保单号 + 事故描述 + 金额），系统校验保单、提取事故信息、计算欺诈评分、检测重复理赔，自动路由到**自动批准 / 转人工审核 / 自动拒绝**。支持理赔员对存疑案件进行人工审核。

### S4.1.2 交付物

| 模块 | 内容 |
|------|------|
| **ClaimsAgent** | 4 个 @Action + 6 条 @State 路由的理赔智能体 |
| **AgentService 扩展** | processClaim + reviewClaim 编排 |
| **理赔 API** | 理赔申请、理赔审核、保单查询（已有） |
| **多 Agent E2E** | 核保→支付→理赔 完整链路 |

### S4.1.3 用户故事

| 编号 | 故事 | 验收标准 |
|------|------|---------|
| US-L1 | 作为用户，我想对已有保单提交理赔申请 | 输入保单号+事故描述+金额，返回 APPROVED/INVESTIGATING/DENIED |
| US-L2 | 作为低欺诈风险用户，我的理赔被自动批准并立即赔付 | fraudScore < 30 → APPROVED，赔付金额 ≤ 保费 × 5 |
| US-L3 | 作为高欺诈风险用户，我的理赔被自动拒绝 | fraudScore ≥ 70 → DENIED，告知拒绝原因 |
| US-L4 | 作为理赔员，我需要审核存疑理赔并做出批准/拒绝决定 | POST /claims/{claimNumber}/review 审核 INVESTIGATING 案件 |
| US-L5 | 作为系统，需要检测重复理赔并自动拒绝 | 同一保单+同一描述再次提交 → DuplicateClaimDetected |

---

## S4.2 ClaimsAgent 设计

### S4.2.1 智能体定义

```
@Agent(description = "理赔处理 Agent")
ClaimsAgent implements StuckHandler
```

- **规划器类型**：默认（Embabel 自动推导数据流）— 框架根据各 @Action 的输入/输出类型自动编排执行计划
- **执行模型**：流水线 + @State 分类路由

### S4.2.2 @Action 工作流

```
用户输入 (key=value 格式: "policy=POL-xxx description=追尾事故 amount=5000")
  │
  ├─ [1] verifyPolicy(UserInput, OperationContext) → Policy
  │      DB 校验保单存在 + ACTIVE 状态 + 在有效期内
  │      → 校验失败返回 PolicyError @State
  │
  ├─ [2] extractClaimInfo(UserInput, OperationContext) → ClaimInfo
  │      LLM 提取结构化事故信息（类型/地点/日期/涉及方）
  │      → LLM 失败时回退到关键词匹配 (extractClaimInfoSimple)
  │      → 提取失败返回 InputError @State
  │
  ├─ [3] calculateFraudScore(UserInput, Policy, ClaimInfo, OperationContext) → Double
  │      四维欺诈评分：理赔/保费比 + 绝对金额 + 信息完整度 + 事故类型
  │      → 评分钳制在 [0, 100]
  │
  ├─ [4] classify(UserInput, Policy, ClaimInfo, Double, OperationContext) → ClaimDecision
  │      入口动作：检查前置错误 → 重复理赔检测 → 按阈值分类到 @State
  │      重复检测：同保单 + 同描述 → DuplicateClaimDetected
  │
  └─ @State 分发（sealed interface ClaimDecision）:
```

### S4.2.3 ClaimInfo（LLM 提取的事故信息）

```java
public record ClaimInfo(
    String accidentType,    // collision, theft, damage, flood, fire, other
    String location,        // 事故地点
    String date,            // 事故日期
    String partiesInvolved  // 涉及方
) {}
```

### S4.2.4 ClaimResult（理赔输出）

```java
public record ClaimResult(
    String claimNumber,      // 理赔单号 CLM-{8位随机大写}
    String claimStatus,      // APPROVED / INVESTIGATING / DENIED / ERROR
    double fraudScore,       // 欺诈评分 [0, 100]
    double approvedAmount,   // 实际赔付金额
    String message           // 结果说明
) {}
```

### S4.2.5 @State 路由定义

```
sealed interface ClaimDecision {
    ┌─────────────────────────────────────────────────────────────┐
    │ 正常路径                                                       │
    ├──────────────────┬──────────────────┬───────────────────────┤
    │ AutoApproved     │ PendingReview    │ AutoDenied            │
    │ fraudScore < 30  │ 30 ≤ score < 70  │ fraudScore ≥ 70       │
    │ → 自动批准赔付    │ → 转人工审核      │ → 自动拒绝             │
    │ → 赔付 ≤ 5×保费  │ → INVESTIGATING  │ → 赔付金额 = 0         │
    ├──────────────────┼──────────────────┼───────────────────────┤
    │ 错误路径                                                       │
    ├──────────────────┼──────────────────┼───────────────────────┤
    │ PolicyError      │ DuplicateClaim   │ InputError            │
    │ 保单校验失败       │ Detected         │ 参数缺失/无效           │
    │ → 返回 ERROR     │ 重复理赔          │ → 返回 ERROR          │
    │                  │ → 返回已有信息    │                       │
    └──────────────────┴──────────────────┴───────────────────────┘
}
```

---

## S4.3 欺诈评分算法

### S4.3.1 四维模型

| 维度 | 因子 | 条件 | 加分 |
|------|------|------|------|
| **理赔/保费比** | 申请金额 ÷ 保费 | > 10 倍 | +40 |
| | | > 5 倍 | +20 |
| | | > 3 倍 | +10 |
| **绝对金额** | 申请金额 | > ¥100,000 | +30 |
| | | > ¥50,000 | +15 |
| **信息完整度** | 涉及方 | "unknown" | +20 |
| | 日期 | 缺失/空 | +15 |
| | 地点 | 缺失/"unknown" | +10 |
| **事故类型** | accidentType | "theft" (盗抢) | +10 |

评分最终钳制在 [0, 100]。

**阈值**：< 30 自动批准 · 30–69 转人工 · ≥ 70 自动拒绝

### S4.3.2 赔付上限

```
赔付上限 = 保费 × 5
实际赔付 = min(申请金额, 赔付上限)
```

### S4.3.3 保费基数

保费不直接从 Claim 获取——Claim 关联 Policy，Policy 的 `premiumAmount` 字段提供保费值。ClaimsAgent 的 `extractClaimInfo` 和 `calculateFraudScore` 通过 @Action 入参中的 Policy 对象来引用保费。

---

## S4.4 理赔审核

### S4.4.1 审核流程

```
理赔员对 INVESTIGATING 理赔单发起审核
  │
  ├─ 校验：claim.status == INVESTIGATING
  ├─ 决定：APPROVED 或 DENIED
  ├─ APPROVED：可设置赔付金额（≤ 保费 × 5）
  ├─ DENIED：赔付金额 = 0，记录拒绝原因
  │
  └─ 返回 ReviewClaimResponse
```

### S4.4.2 ReviewClaimResponse

```java
public record ReviewClaimResponse(
    String claimNumber,
    String status,            // APPROVED / DENIED
    double approvedAmount,
    String message
) {}
```

---

## S4.5 AgentService 扩展

### S4.5.1 processClaim

```
processClaim(userInput):
  1. 解析 key=value 输入 → UserInput
  2. 查找 ClaimsAgent
  3. AgentProcess 创建 + 运行 (+ 120s 超时)
  4. 提取 ClaimResult (process.last)
  5. 失败回退 → Blackboard 错误 → ERROR 响应
```

### S4.5.2 reviewClaim

```
reviewClaim(claimNumber, decision, notes, approvedAmount):
  1. claimRepository.findByClaimNumber(claimNumber)
  2. 状态校验：仅 INVESTIGATING 可审核
  3. APPROVED → paidAmount = approvedAmount (≤ 保费×5)
     DENIED   → paidAmount = 0
  4. 持久化更新，返回 ReviewClaimResponse
```

---

## S4.6 REST API

### S4.6.1 InsuranceController 新增端点 — `/api/insurance`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/claims` | `claims:write` | 提交理赔申请 |
| POST | `/claims/{claimNumber}/review` | `claims:review` | 人工审核理赔单 |

### S4.6.2 POST /api/insurance/claims

**请求**（HTTP Basic Auth：low-risk-user / password）：
```json
{
  "policyNumber": "POL-1719123456-AB3XY9",
  "description": "在京沪高速被后车追尾，后备箱受损严重",
  "claimedAmount": 8000.0
}
```

**响应（低欺诈 — 自动批准）**：
```json
{
  "claimNumber": "CLM-A1B2C3D4",
  "policyNumber": "POL-1719123456-AB3XY9",
  "claimStatus": "APPROVED",
  "claimedAmount": 8000.0,
  "approvedAmount": 8000.0,
  "fraudScore": 15.0,
  "description": "在京沪高速被后车追尾...",
  "createdAt": "2026-06-23T14:30:00",
  "message": "理赔已自动批准。赔付金额 ¥8,000.00。"
}
```

**响应（中欺诈 — 转人工审核）**：
```json
{
  "claimNumber": "CLM-E5F6G7H8",
  "claimStatus": "INVESTIGATING",
  "fraudScore": 45.0,
  "approvedAmount": 0.0,
  "message": "理赔需要人工审核。理赔单已转交理赔员处理。"
}
```

**响应（高欺诈 — 自动拒绝）**：
```json
{
  "claimNumber": "CLM-I9J0K1L2",
  "claimStatus": "DENIED",
  "fraudScore": 85.0,
  "approvedAmount": 0.0,
  "message": "理赔被拒绝：欺诈评分过高。原因：理赔金额远超保费+信息不完整。"
}
```

### S4.6.3 POST /api/insurance/claims/{claimNumber}/review

**请求**（HTTP Basic Auth：claims / claims）：
```json
{
  "decision": "APPROVED",
  "reviewerNotes": "经核实事故属实，批准赔付",
  "approvedAmount": 5000.0
}
```

**响应**：
```json
{
  "claimNumber": "CLM-E5F6G7H8",
  "status": "APPROVED",
  "approvedAmount": 5000.0,
  "message": "理赔审核完成。赔付金额 ¥5,000.00。"
}
```

---

## S4.7 Controller 错误处理规范

所有 InsuranceController 端点遵循统一的错误处理模式：

| 状态码 | 触发条件 |
|--------|---------|
| **200** | 正常处理完成 |
| **400** | 报价单状态不允许操作 / 已过期 / 理赔单状态不允许审核 |
| **404** | 保单/报价单/理赔单未找到 |
| **422** | 输入包含未授权指令 / Agent 返回 ERROR 状态 |
| **500** | 未预期的运行时异常 |

Controller 层捕获所有异常并返回对应状态码，不向客户端暴露内部错误细节。

---

## S4.8 完整平台 API 总览

三个阶段交付后，平台共暴露以下 REST 端点：

| 方法 | 路径 | 权限 | 阶段 | 说明 |
|------|------|------|------|------|
| POST | `/api/chat` | `chat:use` | 二 | AI 客服对话 |
| DELETE | `/api/chat/sessions/{id}` | `chat:use` | 二 | 清除会话 |
| POST | `/api/insurance/underwrite` | `underwriting:write` | 三 | 核保申请 |
| POST | `/api/insurance/claims` | `claims:write` | 四 | 理赔申请 |
| POST | `/api/insurance/claims/{id}/review` | `claims:review` | 四 | 理赔审核 |
| GET | `/api/insurance/policies` | `policies:read` | 三 | 保单列表 |
| GET | `/api/insurance/policies/{id}` | `policies:read` | 三 | 保单详情 |
| POST | `/api/insurance/quotes/{id}/approve` | `underwriting:approve` | 三 | 报价审批 |
| POST | `/api/insurance/pay` | `underwriting:write` | 三 | 支付签单 |
| GET | `/api/insurance/health` | — | 三 | 健康检查 |

---

## S4.9 E2E 业务流程

### S4.9.1 完整链路：投保 → 支付 → 理赔

```
1. POST /api/insurance/underwrite
   user: low-risk-user
   body: {"userId":"low-risk-user","userInput":"给我的Toyota RAV4上车险"}
   → Quote(status=APPROVED, premiumAmount=4800)

2. POST /api/insurance/pay
   user: low-risk-user
   body: {"quoteId":1,"paymentMethod":"ALIPAY"}
   → Policy(policyNumber=POL-xxx, status=ACTIVE)

3. POST /api/insurance/claims
   user: low-risk-user
   body: {"policyNumber":"POL-xxx","description":"追尾事故","claimedAmount":8000}
   → Claim(claimStatus=APPROVED, approvedAmount=8000)
```

### S4.9.2 测试覆盖

| 测试类型 | 文件 | 覆盖范围 |
|---------|------|---------|
| 单元测试 | `ClaimsAgentTest.java` | 欺诈评分逻辑、ClaimInfo 字段赋值、sentinel 保单处理 |
| 集成测试 | `ClaimsAgentIntegrationTest.java` | ClaimsAgent + Embabel 框架 + H2 完整链路 |
| 集成测试 | `UnderwritingAgentIntegrationTest.java` | UnderwritingAgent 完整链路 |
| 集成测试 | `ChatbotAgentIntegrationTest.java` | ChatbotAgent + RAG 检索 |
| E2E | `CompleteE2ETest.java` | 核保 6 条路径 (P1–P6) + 理赔 7 条路径 (C1–C7) |
| 多 Agent | `MultiAgentE2EIntegrationTest.java` | 核保→支付→理赔 三 Agent 协作 |

---

## S4.10 需求追溯矩阵

| 需求编号 | 需求项 | 对应源码 |
|---------|-------|---------|
| S4.2.2 | verifyPolicy @Action | `agent/ClaimsAgent.java` — `verifyPolicy()` |
| S4.2.2 | extractClaimInfo @Action | `agent/ClaimsAgent.java` — `extractClaimInfo()` |
| S4.2.2 | calculateFraudScore @Action | `agent/ClaimsAgent.java` — `calculateFraudScore()` |
| S4.2.2 | classify @Action | `agent/ClaimsAgent.java` — `classify()` |
| S4.2.5 | 6 条 @State 路由 | `agent/ClaimsAgent.java` — sealed interface + 6 records |
| S4.4 | 理赔审核 | `service/AgentService.java` — `reviewClaim()` |
| S4.5 | Agent 编排 | `service/AgentService.java` — `processClaim()` |
| S4.6 | 理赔 REST API | `controller/InsuranceController.java` |
| S4.9.2 | 测试结构 | `test/` 目录下对应文件 |

---

## S4.11 阶段四与前序阶段的关系

```
阶段一 (spec1.md)
  └─ 数据层 + 安全 + LLM 配置

阶段二 (spec2.md)
  └─ ChatbotAgent (Agentic RAG 客服)

阶段三 (spec3.md)
  └─ UnderwritingAgent (智能核保 + 支付签单)

阶段四 (spec4.md) ← 本阶段
  └─ ClaimsAgent (智能理赔 + 审核)
       │
       ▼
  ✅ 三 Agent 智能保险平台完整交付
```

---

## S4.12 三 Agent 能力矩阵

| 能力 | ChatbotAgent | UnderwritingAgent | ClaimsAgent |
|------|:---:|:---:|:---:|
| 规划器 | UTILITY | UTILITY | 默认(数据流) |
| @Action 数量 | 1 | 5 (1入口+4路由) | 4 (4流水线+6路由) |
| @State 路由数 | 无 | 6 | 6 |
| LLM 调用 | Agentic RAG 检索 | 车辆信息提取 | 事故信息提取 |
| 业务规则 | RAG 检索 | 6因子风险评分 | 4维欺诈评分 |
| 外部依赖 | Lucene | JPA Repository | JPA Repository |
| StuckHandler | ✓ | ✓ | ✓ |

**三个 Agent 统一由 AgentService 编排，共享 120s 超时保护和 Blackboard 错误回退机制。**
