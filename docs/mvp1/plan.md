# 阶段一概要设计：数据层与基础设施

> 对应 `spec1.md`，覆盖实体建模、种子数据、安全认证、LLM 模型路由。

---

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot 3.4.0                        │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │ Controller│  │ Service  │  │  Agent   │  │ GuardRail   │  │
│  │ (REST)   │─▶│ (业务)    │─▶│ (Embabel)│  │ (输入/输出) │  │
│  └──────────┘  └──────────┘  └──────────┘  └────────────┘  │
│        │             │              │                        │
│        ▼             ▼              ▼                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Spring Data JPA Repository               │   │
│  │  Customer(客户) │ Vehicle(车辆) │ Quote(报价单) │ Policy(保单) │ Claim(理赔单) │ Doc(文档) │   │
│  └──────────────────────────────────────────────────────┘   │
│        │                                                     │
│        ▼                                                     │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────┐    │
│  │ H2 (内存) │  │ Spring Sec   │  │ DeepSeek LLM       │    │
│  │          │  │ HTTP Basic   │  │ chat / reasoner    │    │
│  └──────────┘  └──────────────┘  └────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**分层职责**：
- **Controller** — REST 端点，参数校验，调用 Service
- **Service** — 业务逻辑编排，Agent 进程调度
- **Agent**（阶段二起实现）— Embabel 智能体，LLM 驱动的理赔/核保流程
- **Repository** — 数据访问，Spring Data JPA 自动代理
- **Entity** — 领域对象，JPA 映射

---

## 2. 数据模型设计

### 2.1 实体关系图

```
Customer/客户 (1) ──── (N) Vehicle/车辆        customer_id FK
Customer/客户 (1) ──── (N) Policy/保单         customer_id FK
Customer/客户 (1) ──── (N) Quote/报价单          customer_id FK
Policy/保单    (1) ──── (N) Claim/理赔单         policy_id FK
Vehicle/车辆   (1) ──── (N) Policy/保单        vehicle_id FK
Vehicle/车辆   (1) ──── (N) Quote/报价单         vehicle_id FK
```

### 2.2 实体清单

| 实体 | 表名 | 主键策略 | 核心字段 |
|------|------|---------|---------|
| Customer（客户） | customer | ID 自增 | userId(UK), name, dateOfBirth, drivingExperienceYears, accidentCount, email, phone |
| Vehicle（车辆） | vehicle | ID 自增 | licensePlate, model, brand, year, vehicleValue, customer(FK) |
| Quote（报价单） | quote | ID 自增 | customer(FK), vehicle(FK), riskScore, premiumAmount, status(PENDING/APPROVED/REFERRED/DECLINED), coverageType, rejectionReason, expiresAt |
| Policy（保单） | policy | ID 自增 | policyNumber(UK), customer(FK), vehicle(FK), coverageType, premiumAmount, effectiveDate, expirationDate, status(ACTIVE/EXPIRED/CANCELLED/SUSPENDED) |
| Claim（理赔单） | claim | ID 自增 | claimNumber(UK), policy(FK), status(PENDING/INVESTIGATING/APPROVED/DENIED/PAID), claimedAmount, paidAmount, fraudScore, description(2000), processId |
| PolicyDocument（知识库文档） | policy_document | ID 自增 | documentName, content(5000), category, language |

### 2.3 Sentinel 模式

Customer（客户）和 Vehicle（车辆）提供静态工厂方法 `lookupFailed()` 返回 sentinel 占位对象，用于 Agent 框架中 LLM 查找失败时的类型槽位占位，避免 UTILITY 规划器因类型缺失而 STUCK。判定方法 `isLookupFailed()` 用于后续逻辑分支判断。

### 2.4 业务编号生成规则

| 实体 | 格式 | 示例 |
|------|------|------|
| Policy（保单） | `POL-{timestamp}-{6位随机大写}` | POL-1719000000-ABCDEF |
| Claim（理赔单） | `CLM-{8位随机大写}` | CLM-A1B2C3D4 |

---

## 3. Repository 层设计

| Repository | 继承 | 自定义查询 |
|-----------|------|-----------|
| CustomerRepository（客户） | JpaRepository\<Customer, Long\> | `findByUserId(String)` |
| VehicleRepository（车辆） | JpaRepository\<Vehicle, Long\> | `findByLicensePlate(String)`, `findByCustomerId(Long)` |
| QuoteRepository（报价单） | JpaRepository\<Quote, Long\> | 继承 CRUD |
| PolicyRepository（保单） | JpaRepository\<Policy, Long\> | `findByPolicyNumber(String)`, `findByCustomerId(Long)` |
| ClaimRepository（理赔单） | JpaRepository\<Claim, Long\> | `findByClaimNumber(String)`, `findByPolicyId(Long)` |
| PolicyDocumentRepository（知识库文档） | JpaRepository\<PolicyDocument, Long\> | 继承 CRUD |

所有 Repository 由 Spring Data JPA 运行时自动生成代理实现，无需手写 SQL。

---

## 4. 种子数据设计

### 4.1 初始化策略

`DataInitializer` 实现 `CommandLineRunner`，在 `customerRepository.count() == 0` 时执行，保证幂等。

### 4.2 预设用户

| userId | 姓名 | 驾龄 | 事故 | 车型 | 风险评分 | 预期核保结果 |
|--------|------|------|------|------|---------|------------|
| low-risk-user | Alice Wang | 15 | 1 | Toyota RAV4 2022 | 15 | APPROVED |
| medium-risk-user | Bob Chen | 4 | 2 | Honda Civic 2018 | 63 | REFERRED |
| high-risk-user | Charlie Zhang | 1 | 3 | BMW X5 2013 | 100 | DECLINED |
| user | John Doe | 15 | 2 | Toyota RAV4 2020 | — | 向后兼容 |
| admin | Jane Smith | 8 | 0 | Tesla Model 3 2022 | — | 向后兼容 |

三种风险等级覆盖核保全部分支：批准、转人工、拒绝。

---

## 5. 安全设计

### 5.1 认证

- HTTP Basic Authentication
- `InMemoryUserDetailsManager`（开发阶段）
- `BCryptPasswordEncoder` 密码编码
- CSRF 关闭
- 仅在非 test / e2e profile 加载

### 5.2 角色与权限

| 用户 | 密码 | 角色 | 权限 |
|------|------|------|------|
| user | password | USER | underwriting:read, chat:use, policies:read |
| underwriter | underwriter | UNDERWRITER | + underwriting:write, underwriting:approve |
| claims | claims | CLAIMS | claims:write, claims:read, claims:review |
| admin | admin | ADMIN | 全部 + rag:admin, policies:write, chat:admin |

### 5.3 角色层级

```
ADMIN > UNDERWRITER > USER
ADMIN > CLAIMS      > USER
```

通过 `RoleHierarchyImpl.fromHierarchy()` 定义，父角色自动继承子角色权限。

### 5.4 URL 访问矩阵

| URL 模式 | 访问控制 |
|----------|---------|
| `/swagger-ui/**`, `/v3/api-docs/**` | permitAll |
| `/api/insurance/health` | permitAll |
| 其余 `/api/**` | authenticated |

方法级权限通过 `@PreAuthorize("hasAuthority('...')")` 控制。

### 5.5 SecurityConfig 结构

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("!test & !e2e")
public class SecurityConfig {
    // SecurityFilterChain  — HTTP Basic + URL 规则
    // UserDetailsService   — 内存用户
    // PasswordEncoder      — BCrypt
    // RoleHierarchy        — ADMIN > UNDERWRITER/CLAIMS > USER
}
```

---

## 6. LLM 模型路由设计

### 6.1 模型注册

| 逻辑角色 | 物理模型 | 用途 |
|---------|---------|------|
| fast | deepseek-chat | 简单查询、RAG 检索 |
| balanced | deepseek-chat | 核保、理赔、聊天、摘要 |
| powerful | deepseek-reasoner | 复杂推理、多步决策 |
| best | deepseek-reasoner | 最高质量 |

角色到模型的映射在 `application.yml` 的 `embabel.models.llms` 段声明，运行时通过 `LlmSelectionService` 按场景选择。

### 6.2 LlmSelectionService 接口

```
forSimpleQuery()        → fast      简单查询
forRetrieval()          → fast      RAG 检索
forSummarization()      → balanced  文档摘要
forUnderwriting()       → balanced  核保决策
forClaims()             → balanced  理赔处理
forChat()               → balanced  客服对话
forComplexReasoning()   → powerful  复杂推理
forAuto()               → auto      框架自动
forComplexity(score)    → 按分数    0-30→fast, 31-60→balanced, 61-100→powerful
```

通过 `LlmOptions.withLlmForRole(role)` 创建选项对象，由 Embabel 框架解析具体模型名。后续 Agent 在 `OperationContext.ai()` 调用时传入对应 `LlmOptions` 即可路由到目标模型。

---

## 7. 数据源配置

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:insurance;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
  jpa:
    hibernate.ddl-auto: update
    open-in-view: false
  h2.console:
    enabled: true
    path: /h2-console
```

**设计要点**：
- **H2 内存模式**：数据不持久化，重启清空，适合开发演示
- **ddl-auto: update**：实体类驱动建表，免手写 DDL
- **open-in-view: false**：避免 OSIV 连接持有
- **DB_CLOSE_DELAY=-1**：连接断开后数据库不关闭

---

## 8. 实施清单

| 序号 | 任务 | 产出 |
|------|------|------|
| 1 | 创建 6 个 Entity + 枚举类 | `entity/` |
| 2 | 创建 6 个 Repository 接口 | `repository/` |
| 3 | 编写 DataInitializer 种子数据 | `config/DataInitializer.java` |
| 4 | 编写 SecurityConfig | `config/SecurityConfig.java` |
| 5 | 编写 LlmSelectionService | `service/LlmSelectionService.java` |
| 6 | 更新 application.yml（含 embabel + claim.rag 段） | `resources/application.yml` |

---

## 9. 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| Sentinel 对象 | Customer（客户）/Vehicle（车辆）提供 `lookupFailed()` 工厂方法 | Agent UTILITY 规划器在 LLM 查找失败时需要类型占位，否则 STUCK |
| 编号生成 | Policy（保单）用时间戳+随机，Claim（理赔单）用纯随机 | Policy 需要时间可追溯性，Claim 只需唯一性 |
| 密码编码 | BCrypt | 业界标准，Spring Security 内置支持 |
| 用户存储 | 内存（开发）/ 可替换 JDBC（生产） | 阶段一快速启动，架构预留替换点 |
| 模型分层 | 逻辑角色解耦物理模型 | yml 改一行即可换模型，代码无感知 |
| H2 数据库 | 内存模式 | 零配置启动，阶段二起可切 PostgreSQL |
