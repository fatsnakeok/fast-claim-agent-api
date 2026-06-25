# 阶段三测试验证文档：UnderwritingAgent — 智能核保 MVP

> 本文档定义 MVP3 功能验收测试的完整用例、执行命令和预期结果。
> 验证对象：`/api/insurance` 下所有核保相关 API。

---

## 1. 测试概述

### 1.1 测试范围

| 类别 | 说明 |
|------|------|
| **功能测试** | 核保流程（低/中/高风险路由）、保单查询、支付、人工审批 |
| **错误路径测试** | 客户不存在、车辆未识别、无效报价单、权限不足 |
| **安全测试** | 认证验证（Basic Auth）、权限控制（RBAC） |
| **边界测试** | 报价单过期、状态校验 |

### 1.2 测试环境

| 组件 | 配置 |
|------|------|
| 数据库 | H2 内存数据库（`jdbc:h2:mem:claimdb`） |
| 服务端口 | `8080` |
| 认证方式 | HTTP Basic Auth |
| API Base URL | `http://localhost:8080/api/insurance` |

### 1.3 测试角色

| 角色 | 用户名 | 密码 | 权限 |
|------|--------|------|------|
| 核保员 | `underwriter` | `underwriter` | `underwriting:write`, `underwriting:approve`, `policies:read` |
| 普通用户 | `user` | `password` | `policies:read` |

---

## 2. 预置测试数据

应用启动时通过 `DataInitializer` 自动初始化以下测试数据：

### 2.1 客户数据

| userId | 姓名 | 年龄 | 驾龄(年) | 事故次数 | 风险特征 |
|--------|------|------|----------|----------|----------|
| `low-risk-user` | Alice Wang | 41 | 15 | 1 | 低风险，驾龄长 |
| `medium-risk-user` | Bob Chen | 26 | 4 | 2 | 中风险，年轻人 |
| `high-risk-user` | Charlie Zhang | 21 | 1 | 3 | 高风险，年轻新手 |

### 2.2 车辆数据

| 车牌号 | 品牌 | 型号 | 出厂年份 | 车辆价值 | 所属客户 |
|--------|------|------|----------|----------|----------|
| `LOW001` | Toyota | RAV4 | 2022 | ¥300,000 | low-risk-user |
| `MED001` | Honda | Civic | 2018 | ¥180,000 | medium-risk-user |
| `HIGH001` | BMW | X5 | 2013 | ¥600,000 | high-risk-user |

### 2.3 预期风险评分

| 用户 | 年龄分 | 驾龄分 | 事故分 | 车龄分 | 车价分 | **总分** | **核保结果** |
|------|--------|--------|--------|--------|--------|----------|--------------|
| low-risk-user (41岁) | 0 | 0 | 15 | 0 | 0 | **15** | APPROVED |
| medium-risk-user (26岁) | 15 | 10 | 30 | 8 | 0 | **63** | REFERRED |
| high-risk-user (21岁) | 25 | 20 | 45 | 15 | 10 | **115→100** | DECLINED |

---

## 3. API 测试用例

### 3.1 US-U1：低风险用户自动批准

**描述**：低风险用户（41岁、15年驾龄、1次事故）应自动批准

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"我想给我的 Toyota RAV4 上保险，车牌 LOW001"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 200）：
```json
{
  "quoteId": 1,
  "status": "APPROVED",
  "riskScore": 15.0,
  "premiumAmount": 4800.0,
  "message": "核保通过。保费 ¥4800.00，报价单有效期 30 天。"
}
```

**验证点**：
- [ ] HTTP 状态码 200
- [ ] `status` 为 `APPROVED`
- [ ] `riskScore` = 15.0
- [ ] `premiumAmount` = 4800.0（计算：300000 × 2% × 0.8 × 1.0）

---

### 3.2 US-U2：中风险用户转人工

**描述**：中风险用户（26岁、4年驾龄、2次事故）应转人工审核

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"medium-risk-user","userInput":"给我的 Honda Civic 上保险"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 200）：
```json
{
  "quoteId": 2,
  "status": "REFERRED",
  "riskScore": 63.0,
  "premiumAmount": 3600.0,
  "message": "需要人工审核。您的申请已转交核保员处理。"
}
```

**验证点**：
- [ ] HTTP 状态码 200
- [ ] `status` 为 `REFERRED`
- [ ] `riskScore` = 63.0
- [ ] `premiumAmount` = 3600.0（计算：180000 × 2% × 1.0 × 1.0）

---

### 3.3 US-U3：高风险用户自动拒绝

**描述**：高风险用户（21岁、1年驾龄、3次事故）应自动拒绝

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"high-risk-user","userInput":"我想给我的 BMW X5 上保险，车牌 HIGH001"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 200）：
```json
{
  "quoteId": 3,
  "status": "DECLINED",
  "riskScore": 100.0,
  "premiumAmount": 18000.0,
  "message": "核保不通过：风险评分过高。年龄21岁(年轻驾驶员)+驾龄1年+3次历史事故+13年车龄旧车+高价值车型"
}
```

**验证点**：
- [ ] HTTP 状态码 200
- [ ] `status` 为 `DECLINED`
- [ ] `riskScore` = 100.0（钳制后）
- [ ] `premiumAmount` = 18000.0
- [ ] `message` 包含拒绝原因

---

### 3.4 US-U4：保单查询

#### 3.4.1 按 userId 查询保单列表

**请求**：
```bash
curl -u user:password \
  "http://localhost:8080/api/insurance/policies?userId=low-risk-user"
```

**预期响应**（HTTP 200）：
```json
[
  {
    "policyNumber": "POL-1750000000000-ABCDEF",
    "customerName": "Alice Wang",
    "vehicleModel": "Toyota RAV4",
    "coverageType": "COMPREHENSIVE",
    "premiumAmount": 4800.0,
    "effectiveDate": "2026-06-25T10:30:00",
    "expirationDate": "2026-12-31T23:59:59",
    "status": "ACTIVE"
  }
]
```

> **注意**：`policyNumber` 实际格式为 `POL-{System.currentTimeMillis()}-{6位随机大写字母}`，例如 `POL-1750000000000-ABCDEF`。请以实际返回值为准。

**验证点**：
- [ ] HTTP 状态码 200
- [ ] 返回数组（支付后有数据）
- [ ] `status` 为 `ACTIVE`
- [ ] `customerName` 为 `Alice Wang`

#### 3.4.2 按保单号查询保单详情

**请求**：
```bash
# 从 3.5 支付接口的响应中获取实际 policyNumber
curl -u user:password \
  "http://localhost:8080/api/insurance/policies/POL-1750000000000-ABCDEF"
```

**预期响应**（HTTP 200）：
```json
{
  "policyNumber": "POL-1750000000000-ABCDEF",
  "customerName": "Alice Wang",
  "vehicleModel": "Toyota RAV4",
  "coverageType": "COMPREHENSIVE",
  "premiumAmount": 4800.0,
  "effectiveDate": "2026-06-25T10:30:00",
  "expirationDate": "2026-12-31T23:59:59",
  "status": "ACTIVE"
}
```

#### 3.4.3 查询不存在的保单

**请求**：
```bash
curl -u user:password \
  "http://localhost:8080/api/insurance/policies/POL-NONEXISTENT"
```

**预期响应**（HTTP 400）：
```json
{
  "error": "BIZ_ERROR",
  "message": "保单不存在: POL-NONEXISTENT"
}
```

> **注意**：`PolicyService.findByPolicyNumber` 抛出 `BizException`，默认 `errorCode = "BIZ_ERROR"`，被 `GlobalExceptionHandler` 映射为 HTTP 400。

---

### 3.5 US-U5：支付 + 签发保单

**前置条件**：先执行 US-U1 获取有效的 APPROVED 报价单

**请求**：
```bash
# 1. 先核保获取 quoteId
UW_RESPONSE=$(curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4 车牌 LOW001"}' \
  http://localhost:8080/api/insurance/underwrite)

# 2. 从响应中提取 quoteId（假设为 1）
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"quoteId":1}' \
  http://localhost:8080/api/insurance/pay
```

**预期响应**（HTTP 200）：
```json
{
  "policyNumber": "POL-1750000000000-ABCDEF",
  "customerName": "Alice Wang",
  "vehicleModel": "Toyota RAV4",
  "coverageType": "COMPREHENSIVE",
  "premiumAmount": 4800.0,
  "effectiveDate": "2026-06-25T10:30:00",
  "expirationDate": "2026-12-31T23:59:59",
  "status": "ACTIVE"
}
```

> **注意**：`policyNumber` 由 `Policy.generatePolicyNumber()` 生成，格式为 `POL-{System.currentTimeMillis()}-{6位随机大写字母}`，例如 `POL-1750000000000-ABCDEF`。文档中示例为占位符，以实际返回为准。

**验证点**：
- [ ] HTTP 状态码 200
- [ ] `policyNumber` 格式为 `POL-{时间戳}-{6位大写字母}`
- [ ] `effectiveDate` 为当前时间
- [ ] `expirationDate` 为次年同期（当年实际天数）
- [ ] `status` 为 `ACTIVE`

---

### 3.6 US-U6：人工审批

**前置条件**：执行 US-U2 获取 REFERRED 报价单

**请求**：
```bash
# 1. 先核保获取 REFERRED 报价单
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"medium-risk-user","userInput":"Honda Civic"}' \
  http://localhost:8080/api/insurance/underwrite

# 2. 人工审批（假设 quoteId 为 2）
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"overridePremiumAmount":5000}' \
  http://localhost:8080/api/insurance/quotes/2/approve
```

**预期响应**（HTTP 200）：
```json
{
  "quoteId": 2,
  "status": "APPROVED",
  "premiumAmount": 5000.0,
  "message": "审批通过，报价单已批准"
}
```

**验证点**：
- [ ] HTTP 状态码 200
- [ ] `status` 为 `APPROVED`
- [ ] `premiumAmount` 为覆盖后的 5000.0

---

### 3.7 US-U7：健康检查

**请求**：
```bash
curl http://localhost:8080/api/insurance/health
```

**预期响应**（HTTP 200）：
```
OK
```

**验证点**：
- [ ] 无需认证即可访问
- [ ] 返回 `OK`

---

## 4. 错误路径测试

### 4.1 客户不存在

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"unknown-user","userInput":"我想给我的 Toyota RAV4 上保险"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 422）：
```json
{
  "quoteId": null,
  "status": "ERROR",
  "riskScore": 0.0,
  "premiumAmount": 0.0,
  "message": "客户不存在: unknown-user"
}
```

**验证点**：
- [ ] HTTP 状态码 422（Unprocessable Entity）
- [ ] `status` 为 `ERROR`

---

### 4.2 车辆信息无法识别

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"你好，天气不错"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 422）：
```json
{
  "quoteId": null,
  "status": "ERROR",
  "riskScore": 0.0,
  "premiumAmount": 0.0,
  "message": "车辆信息提取失败: 未识别到车辆信息，请提供品牌、型号或车牌号"
}
```

**验证点**：
- [ ] HTTP 状态码 422
- [ ] `status` 为 `ERROR`
- [ ] `message` 包含车辆识别失败原因

---

### 4.3 车辆不属于当前客户

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"给我的 BMW X5 上保险"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 422）：
```json
{
  "quoteId": null,
  "status": "ERROR",
  "riskScore": 0.0,
  "premiumAmount": 0.0,
  "message": "车辆未找到 — brand: BMW, model: X5, plate: null"
}
```

---

### 4.4 报价单不存在（支付）

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"quoteId":9999}' \
  http://localhost:8080/api/insurance/pay
```

**预期响应**（HTTP 400）：
```json
{
  "error": "BIZ_ERROR",
  "message": "报价单不存在: 9999"
}
```

---

### 4.5 报价单状态非 APPROVED（支付）

**前置条件**：执行 US-U3 获取 DECLINED 报价单，然后尝试支付

**请求**：
```bash
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"quoteId":3}' \
  http://localhost:8080/api/insurance/pay
```

**预期响应**（HTTP 400）：
```json
{
  "error": "BIZ_ERROR",
  "message": "仅已批准的报价单可支付，当前状态: DECLINED"
}
```

---

## 5. 安全测试

### 5.1 无认证被拒

**请求**：
```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 401）：
```
Unauthorized
```

---

### 5.2 权限不足

**请求**：
```bash
curl -u user:password -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4"}' \
  http://localhost:8080/api/insurance/underwrite
```

**预期响应**（HTTP 403）：
```
Forbidden
```

**验证点**：
- [ ] 普通用户无 `underwriting:write` 权限
- [ ] 返回 HTTP 403

---

### 5.3 审批权限校验

**请求**：
```bash
curl -u user:password -X POST \
  -H 'Content-Type: application/json' \
  -d '{}' \
  http://localhost:8080/api/insurance/quotes/1/approve
```

**预期响应**（HTTP 403）：
```
Forbidden
```

---

## 6. 边界测试

### 6.1 报价单过期校验

**说明**：Quote 有效期为 30 天，过期后不可支付或审批

**验证方法**：
1. 获取一个 APPROVED 报价单的 `quoteId`
2. 直接修改数据库 `quote.expires_at` 为过去时间
3. 尝试支付，应返回 `报价单已过期`

### 6.2 保费精度测试

**验证点**：
- [ ] 使用 BigDecimal 计算，保留两位小数
- [ ] 四舍五入符合 HALF_UP 规则

| 车辆价值 | 风险评分 | 险种 | 计算过程 | 预期保费 |
|----------|----------|------|----------|----------|
| ¥123,456 | 50 | COMPREHENSIVE | 123456 × 0.02 × 1.0 × 1.0 = 2469.12 | ¥2469.12 |
| ¥99,999 | 30 | THIRD_PARTY | 99999 × 0.02 × 0.8 × 0.5 = 799.992 → HALF_UP | ¥799.99 |
| ¥500,000 | 80 | COMPREHENSIVE | 500000 × 0.02 × 1.5 × 1.0 = 15000.00 | ¥15000.00 |

> **注意**：BigDecimal 使用 `RoundingMode.HALF_UP` 进行四舍五入，保留 2 位小数。0.992 的第三位是 2 < 5，舍入为 799.99。

---

## 7. 测试执行命令

### 7.1 启动服务

```bash
cd /Users/liuxin/Documents/ideaProject/fast-claim-agent-api
./mvnw spring-boot:run
```

### 7.2 运行单元测试

```bash
# 运行所有测试
./mvnw test

# 仅运行核保相关测试
./mvnw test -Dtest=UnderwritingAgentTest

# 运行特定测试方法
./mvnw test -Dtest=UnderwritingAgentTest#lowRiskUserShouldGetApproved
```

### 7.3 运行集成测试

```bash
# 启动服务后运行 E2E 测试
./mvnw verify -P integration-tests
```

### 7.4 完整验证脚本

```bash
#!/bin/bash
BASE_URL="http://localhost:8080/api/insurance"
AUTH="underwriter:underwriter"
USER_AUTH="user:password"

echo "=== MVP3 核保功能验证脚本 ==="
echo ""

# US-U1: 低风险自动批准
echo "[US-U1] 低风险用户自动批准"
curl -s -u $AUTH -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4 车牌 LOW001"}' \
  $BASE_URL/underwrite | jq .
echo ""

# US-U2: 中风险转人工
echo "[US-U2] 中风险用户转人工"
curl -s -u $AUTH -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"medium-risk-user","userInput":"Honda Civic"}' \
  $BASE_URL/underwrite | jq .
echo ""

# US-U3: 高风险自动拒绝
echo "[US-U3] 高风险用户自动拒绝"
curl -s -u $AUTH -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"high-risk-user","userInput":"BMW X5 车牌 HIGH001"}' \
  $BASE_URL/underwrite | jq .
echo ""

# US-U4: 保单查询
echo "[US-U4] 保单查询"
curl -s -u $USER_AUTH "$BASE_URL/policies?userId=low-risk-user" | jq .
echo ""

# US-U7: 健康检查
echo "[US-U7] 健康检查"
curl -s $BASE_URL/health
echo ""
echo ""

# 错误路径测试
echo "[ERROR] 客户不存在"
curl -s -u $AUTH -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"unknown-user","userInput":"Toyota RAV4"}' \
  $BASE_URL/underwrite | jq .
echo ""

echo "[ERROR] 车辆信息缺失"
curl -s -u $AUTH -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"你好"}' \
  $BASE_URL/underwrite | jq .
echo ""

# 安全测试
echo "[SECURITY] 无认证被拒"
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4"}' \
  $BASE_URL/underwrite
echo ""

echo "[SECURITY] 权限不足"
curl -s -u $USER_AUTH -X POST -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4"}' \
  $BASE_URL/underwrite
echo ""

echo "=== 验证完成 ==="
```

---

## 8. 测试覆盖矩阵

| 测试用例 | 对应需求 | 验证点 | 状态 |
|---------|---------|--------|------|
| `lowRiskUserShouldGetApproved` | US-U1 | APPROVED + ¥4,800 | ⬜ |
| `mediumRiskUserShouldBeReferred` | US-U2 | REFERRED + ¥3,600 | ⬜ |
| `highRiskUserShouldBeDeclined` | US-U3 | DECLINED + ¥18,000 | ⬜ |
| `shouldQueryPoliciesByUserId` | US-U4 | GET /policies 返回 200 | ⬜ |
| `shouldReturn404ForUnknownPolicyNumber` | US-U4 | 不存在的保单号 404 | ⬜ |
| `shouldApproveQuote` | US-U6 | REFERRED → APPROVED | ⬜ |
| `shouldPayAndIssuePolicy` | US-U5 | APPROVED → ACTIVE | ⬜ |
| `shouldRejectPaymentForUnapprovedQuote` | US-U5 | 无效状态返回 400 | ⬜ |
| `unknownCustomerShouldReturn422` | 错误路径 | 不存在的用户 422 | ⬜ |
| `noVehicleInfoShouldReturn422` | 错误路径 | 无车辆关键词 422 | ⬜ |
| `vehicleNotBelongToCustomerShouldReturn422` | 错误路径 | 车辆归属校验 422 | ⬜ |
| `shouldRejectPaymentForNonExistentQuote` | 错误路径 | quoteId 不存在 400 | ⬜ |
| `shouldRejectWithoutAuth` | 安全测试 | 无认证 401 | ⬜ |
| `shouldRejectWithInsufficientPermissions` | 安全测试 | 权限不足 403 | ⬜ |
| `healthCheckShouldReturnOK` | US-U7 | 健康检查无需认证 | ⬜ |

---

## 9. 验证检查清单

### 9.1 功能验证

- [ ] 低风险用户（low-risk-user）核保自动通过，状态为 APPROVED
- [ ] 中风险用户（medium-risk-user）核保转人工，状态为 REFERRED
- [ ] 高风险用户（high-risk-user）核保自动拒绝，状态为 DECLINED
- [ ] 风险评分计算正确（15/63/100）
- [ ] 保费计算使用 BigDecimal，保留两位小数

### 9.2 保单生命周期

- [ ] 支付后生成保单，状态为 ACTIVE
- [ ] 保单号格式为 `POL-{时间戳}-{6位大写字母}`，如 `POL-1750000000000-ABCDEF`
- [ ] 有效期为当年实际天数（`LocalDate.now().lengthOfYear()`）
- [ ] 可以按 userId 查询保单列表
- [ ] 可以按保单号查询保单详情

### 9.3 错误处理

- [ ] 客户不存在返回 422 ERROR
- [ ] 车辆信息无法识别返回 422 ERROR
- [ ] 车辆不属于当前客户返回 422 ERROR
- [ ] 报价单不存在支付返回 400
- [ ] 非 APPROVED 状态支付返回 400

### 9.4 安全控制

- [ ] 无认证访问受保护接口返回 401
- [ ] 普通用户调用核保接口返回 403
- [ ] 普通用户调用审批接口返回 403
- [ ] 健康检查接口无需认证

### 9.5 日志验证

检查日志输出包含以下关键信息：
- [ ] `核保申请 — userId: xxx, input: xxx`
- [ ] `风险评分计算 — customer: xxx, score: xxx`
- [ ] `核保通过 — quoteId: xxx` 或 `核保拒绝 — quoteId: xxx`
- [ ] `支付处理 — quoteId: xxx`
- [ ] `保单签发成功 — policyNumber: xxx`

---

## 10. 故障排查

### 10.1 常见问题

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| 测试数据未初始化 | `DataInitializer` 未执行 | 检查 `@PostConstruct` 或 `ApplicationRunner` |
| LLM 调用超时 | DeepSeek API 不可用 | 检查 `DEEPSEEK_API_KEY` 环境变量 |
| 报价单状态错误 | 数据库未重置 | 每次测试前清空 H2 数据库 |
| 权限不足 | Spring Security 配置错误 | 检查 `@PreAuthorize` 注解 |

### 10.2 调试模式

```bash
# 启用详细日志
export LOGGING_LEVEL_COM_FASTCLAIM=TRACE
export LOGGING_LEVEL_COM_EMBABEL_AGENT=TRACE

# 启动应用
./mvnw spring-boot:run
```

### 10.3 H2 控制台

测试期间可访问 H2 Web Console 检查数据：
- URL: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:claimdb`
- Username: `sa`
- Password: (空)

---

## 附录 A：完整 curl 命令参考

```bash
# 核保申请
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"low-risk-user","userInput":"Toyota RAV4 车牌 LOW001"}' \
  http://localhost:8080/api/insurance/underwrite

# 查询保单列表
curl -u user:password \
  "http://localhost:8080/api/insurance/policies?userId=low-risk-user"

# 查询保单详情（使用支付后返回的实际 policyNumber）
curl -u user:password \
  "http://localhost:8080/api/insurance/policies/POL-1750000000000-ABCDEF"

# 人工审批
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"overridePremiumAmount":5000}' \
  http://localhost:8080/api/insurance/quotes/2/approve

# 支付
curl -u underwriter:underwriter -X POST \
  -H 'Content-Type: application/json' \
  -d '{"quoteId":1}' \
  http://localhost:8080/api/insurance/pay

# 健康检查
curl http://localhost:8080/api/insurance/health
```

---

## 附录 B：测试用户凭据汇总

| 用途 | 用户名 | 密码 | 角色 | 权限 |
|------|--------|------|------|------|
| 核保操作 | `underwriter` | `underwriter` | 核保员 | `underwriting:write`, `underwriting:approve`, `policies:read` |
| 查询操作 | `user` | `password` | 普通用户 | `policies:read` |
