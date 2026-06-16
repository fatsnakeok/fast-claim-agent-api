# 阶段一需求规格：数据层与基础设施

> 对应 `doc/业务分析.md` §7.1 阶段一，包含实体建模、种子数据、安全认证、LLM 模型路由。

---

## S1.1 数据初始化

### S1.1.1 实体建模

#### Customer（客户）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | Long | PK, 自增 | — |
| userId | String | NOT NULL, UNIQUE | 业务唯一标识 |
| name | String | NOT NULL | 姓名 |
| dateOfBirth | LocalDate | NOT NULL | 出生日期 |
| drivingExperienceYears | int | NOT NULL | 驾龄（年） |
| accidentCount | int | NOT NULL | 历史事故次数 |
| email | String | NOT NULL | 邮箱 |
| phone | String | NOT NULL | 手机号 |

派生方法：`getAge()` — 基于 dateOfBirth 和当前日期动态计算年龄。

关联关系：
- `@OneToMany(mappedBy="customer")` → List\<Vehicle\>
- `@OneToMany(mappedBy="customer")` → List\<Policy\>
- `@OneToMany(mappedBy="customer")` → List\<Quote\>

静态工厂方法：
- `Customer.lookupFailed()` — 返回 sentinel 占位对象（userId=`__sentinel__`），用于 Agent 框架在查找失败时占位 Blackboard 类型槽位，避免 UTILITY 规划器因缺失类型而 STUCK
- `Customer.isLookupFailed(Customer)` — 判定是否为 sentinel 对象

---

#### Vehicle（车辆）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | Long | PK, 自增 | — |
| licensePlate | String | NOT NULL | 车牌号 |
| model | String | NOT NULL | 车型（如 RAV4、Civic） |
| brand | String | NOT NULL | 品牌（如 Toyota、Honda） |
| year | int | NOT NULL, @Column(name="vehicle_year") | 出厂年份 |
| vehicleValue | double | NOT NULL, @Column(name="vehicle_value") | 车辆价值（CNY） |
| customer | Customer | NOT NULL, FK @ManyToOne | 所属客户 |

静态工厂方法：
- `Vehicle.lookupFailed()` — sentinel 占位（licensePlate=`__sentinel__`）
- `Vehicle.isLookupFailed(Vehicle)` — sentinel 判定

---

#### Quote（报价单）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | Long | PK, 自增 | — |
| customer | Customer | NOT NULL, FK @ManyToOne | 投保客户 |
| vehicle | Vehicle | NOT NULL, FK @ManyToOne | 投保车辆 |
| riskScore | double | NOT NULL | 风险评分 [0, 100] |
| premiumAmount | double | NOT NULL | 保费金额（CNY） |
| status | QuoteStatus | NOT NULL, @Enumerated(STRING) | 见下方状态枚举 |
| coverageType | String | NOT NULL | 险种（COMPREHENSIVE/THIRD_PARTY/THIRD_PARTY_FIRE_THEFT） |
| createdAt | LocalDateTime | NOT NULL, @PrePersist 自动填充 | 创建时间 |
| rejectionReason | String | 可空 | 拒绝原因 |
| expiresAt | LocalDateTime | 构造函数中设为 createdAt+30天 | 报价有效期 |

**QuoteStatus 枚举**：`PENDING` / `APPROVED` / `REFERRED` / `DECLINED`

---

#### Policy（保单）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | Long | PK, 自增 | — |
| policyNumber | String | NOT NULL, UNIQUE | 保单号（格式：POL-{timestamp}-{6位随机大写}） |
| customer | Customer | NOT NULL, FK @ManyToOne | 投保人 |
| vehicle | Vehicle | NOT NULL, FK @ManyToOne | 被保车辆 |
| coverageType | String | NOT NULL | 险种 |
| premiumAmount | double | NOT NULL | 保费金额 |
| effectiveDate | LocalDateTime | NOT NULL | 生效日期 |
| expirationDate | LocalDateTime | NOT NULL | 到期日期（默认 +1年） |
| status | PolicyStatus | NOT NULL, @Enumerated(STRING) | 见下方状态枚举 |

关联关系：`@OneToMany(mappedBy="policy", cascade=ALL)` → List\<Claim\>

**PolicyStatus 枚举**：`ACTIVE` / `EXPIRED` / `CANCELLED` / `SUSPENDED`

---

#### Claim（理赔单）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | Long | PK, 自增 | — |
| claimNumber | String | NOT NULL, UNIQUE | 理赔单号（格式：CLM-{8位随机大写}） |
| policy | Policy | NOT NULL, FK @ManyToOne | 关联保单 |
| status | ClaimStatus | NOT NULL, @Enumerated(STRING) | 见下方状态枚举 |
| claimedAmount | double | NOT NULL | 申请理赔金额 |
| paidAmount | double | — | 实际赔付金额 |
| fraudScore | double | NOT NULL | 欺诈评分 [0, 100] |
| description | String(2000) | NOT NULL | 事故描述 |
| createdAt | LocalDateTime | NOT NULL | 创建时间（构造函数自动填充） |
| processId | String | — | 关联的 AgentProcess ID |

**ClaimStatus 枚举**：`PENDING` / `INVESTIGATING` / `APPROVED` / `DENIED` / `PAID`

---

#### PolicyDocument（知识库文档引用）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | Long | PK, 自增 | — |
| documentName | String | NOT NULL | 文档名称 |
| content | String(5000) | NOT NULL | 文档内容 |
| category | String | NOT NULL | 分类（policy/claims/faq） |
| language | String | NOT NULL | 语言 |

---

### S1.1.2 ER 关系图

```
Customer (1) ──── (N) Vehicle        customer_id FK
Customer (1) ──── (N) Policy         customer_id FK
Customer (1) ──── (N) Quote          customer_id FK
Policy    (1) ──── (N) Claim         policy_id FK
Vehicle   (1) ──── (N) Policy        vehicle_id FK
Vehicle   (1) ──── (N) Quote         vehicle_id FK
```

---

### S1.1.3 JPA Repository 层

| Repository | 继承 | 关键查询方法 |
|-----------|------|-------------|
| CustomerRepository | JpaRepository\<Customer, Long\> | `findByUserId(String)` |
| VehicleRepository | JpaRepository\<Vehicle, Long\> | `findByLicensePlate(String)`, `findByCustomerId(Long)` |
| QuoteRepository | JpaRepository\<Quote, Long\> | （继承 CRUD） |
| PolicyRepository | JpaRepository\<Policy, Long\> | `findByPolicyNumber(String)`, `findByCustomerId(Long)` |
| ClaimRepository | JpaRepository\<Claim, Long\> | `findByClaimNumber(String)`, `findByPolicyId(Long)` |
| PolicyDocumentRepository | JpaRepository\<PolicyDocument, Long\> | （继承 CRUD） |

---

### S1.1.4 种子数据

`DataInitializer` 实现 `CommandLineRunner`，仅在数据库为空时执行（`customerRepository.count() == 0`）。

#### 预设用户矩阵

| userId | 姓名 | 出生日期 | 年龄 | 驾龄 | 事故 | 车型 | 年份 | 车价 | 车牌 | 风险评分 | 预期核保结果 |
|--------|------|---------|------|------|------|------|------|------|------|---------|------------|
| low-risk-user | Alice Wang | 1985-03-15 | 41 | 15 | 1 | Toyota RAV4 | 2022 | ¥300k | LOW001 | 15 | APPROVED |
| medium-risk-user | Bob Chen | 1999-07-20 | 27 | 4 | 2 | Honda Civic | 2018 | ¥180k | MED001 | 63 | REFERRED |
| high-risk-user | Charlie Zhang | 2005-01-10 | 21 | 1 | 3 | BMW X5 | 2013 | ¥600k | HIGH001 | 100 | DECLINED |
| user | John Doe | 1985-05-15 | 41 | 15 | 2 | Toyota RAV4 | 2020 | ¥250k | ABC123 | — | (兼容) |
| admin | Jane Smith | 1990-10-20 | 36 | 8 | 0 | Tesla Model 3 | 2022 | ¥450k | XYZ789 | — | (兼容) |

**需求要点**：
- 三种风险等级覆盖核保全部决策路径（批准/转人工/拒绝）
- 两个遗留用户（user/admin）用于向后兼容
- 每个用户关联一辆车
- H2 内存数据库（`jdbc:h2:mem:insurance`），`ddl-auto: update` 自动建表
- H2 Console 在 `/h2-console` 可用

---

## S1.2 安全认证

### S1.2.1 认证机制

- **方式**：HTTP Basic Authentication
- **用户存储**：`InMemoryUserDetailsManager`（内存模式）
- **密码编码**：`BCryptPasswordEncoder`
- **CSRF**：关闭（`AbstractHttpConfigurer::disable`）
- **生效条件**：仅在非 test / e2e profile 下加载（`@Profile("!test & !e2e")`）

### S1.2.2 角色与权限矩阵

| 用户名 | 密码 | 角色 | 权限明细 |
|--------|------|------|---------|
| user | password | USER | `underwriting:read`, `chat:use`, `policies:read` |
| underwriter | underwriter | UNDERWRITER | `underwriting:write`, `underwriting:approve`, `underwriting:read`, `chat:use`, `policies:read` |
| claims | claims | CLAIMS | `claims:write`, `claims:read`, `claims:review`, `chat:use`, `policies:read` |
| admin | admin | ADMIN | 全部权限 + `rag:admin` + `policies:write` + `chat:admin` |

### S1.2.3 角色层级继承

```
ADMIN > UNDERWRITER
ADMIN > CLAIMS
ADMIN > USER
UNDERWRITER > USER
CLAIMS > USER
```

通过 `RoleHierarchyImpl.fromHierarchy()` 定义，集成到 `DefaultMethodSecurityExpressionHandler`。

### S1.2.4 方法级安全

- 启用 `@EnableMethodSecurity(prePostEnabled = true)`
- 各 Controller 方法通过 `@PreAuthorize("hasAuthority('...')")` 进行细粒度控制

### S1.2.5 URL 访问控制

| URL 模式 | 访问控制 |
|----------|---------|
| `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`, `/v3/api-docs.yaml` | permitAll |
| `/api/insurance/health` | permitAll |
| `/api/chat/**` | authenticated |
| `/api/insurance/**` | authenticated |
| 其余 | authenticated |

---

## S1.3 LLM 模型分层

### S1.3.1 模型角色定义

在 `application.yml` 中声明 Embabel 模型角色：

```yaml
embabel:
  models:
    default-llm: deepseek-chat
    llms:
      fast: deepseek-chat        # 轻量快速任务
      balanced: deepseek-chat    # 常规任务
      powerful: deepseek-reasoner # 复杂推理
      best: deepseek-reasoner
  agent:
    platform:
      models:
        deepseek:
          api-key: ${DEEPSEEK_API_KEY}
```

### S1.3.2 LlmSelectionService 接口

| 方法 | 模型角色 | 适用场景 |
|------|---------|---------|
| `forSimpleQuery()` | fast | 简单查询、快速问答 |
| `forRetrieval()` | fast | RAG 检索任务 |
| `forSummarization()` | balanced | 文档摘要 |
| `forComplexReasoning()` | powerful | 复杂推理 |
| `forUnderwriting()` | balanced | 核保决策 |
| `forClaims()` | balanced | 理赔处理 |
| `forChat()` | balanced | 通用客服对话 |
| `forEmbedding()` | embedding | 嵌入操作（预留） |
| `forAuto()` | auto | 框架自动选择 |
| `forModel(String)` | 指定模型 | 按模型名选择 |
| `forComplexity(int)` | 按复杂度 | 0-30→fast, 31-60→balanced, 61-100→powerful |

### S1.3.3 角色常量

```java
public static final String ROLE_FAST      = "fast";
public static final String ROLE_BALANCED  = "balanced";
public static final String ROLE_POWERFUL  = "powerful";
public static final String ROLE_EMBEDDING = "embedding";
```

通过 `LlmOptions.withLlmForRole(role)` 或 `LlmOptions.withAutoLlm()` 创建配置对象，由 Embabel 框架根据 `application.yml` 中的映射解析为具体模型名。

### S1.3.4 模型选型说明

| DeepSeek 模型 | 用途 | 注册角色 |
|---------------|------|---------|
| deepseek-chat | 通用对话，响应快 | fast, balanced |
| deepseek-reasoner | 推理增强，深度思考 | powerful, best |

---

## S1.4 配置文件（application.yml）

```yaml
spring:
  application.name: smart-insurance-platform
  datasource:
    url: jdbc:h2:mem:insurance;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate.ddl-auto: update
    open-in-view: false
    h2.console:
      enabled: true
      path: /h2-console
server:
  port: 8080
```

**关键设计决策**：
- H2 内存模式：数据不持久化，重启即清空，适合开发与演示
- `ddl-auto: update`：根据实体类自动创建/更新表结构，无需手写 DDL
- `open-in-view: false`：避免 OSIV 带来的连接持有和懒加载问题
- `DB_CLOSE_DELAY=-1`：保持数据库在连接断开后不关闭，支持多次查询

---

## S1.5 需求追溯矩阵

| 需求编号 | 需求项 | 对应源码 |
|---------|-------|---------|
| S1.1.1 | 6个实体建模 | `entity/Customer.java`, `Vehicle.java`, `Quote.java`, `Policy.java`, `Claim.java`, `PolicyDocument.java` |
| S1.1.3 | 6个 Repository | `repository/` 目录下对应文件 |
| S1.1.4 | 5个种子用户 + 5辆车 | `config/DataInitializer.java` |
| S1.2.1 | HTTP Basic 认证 | `config/SecurityConfig.java` |
| S1.2.2 | 4角色权限矩阵 | `config/SecurityConfig.java` — `userDetailsService()` |
| S1.2.3 | 角色层级继承 | `config/SecurityConfig.java` — `roleHierarchy()` |
| S1.2.4 | 方法级安全 | `config/SecurityConfig.java` — `@EnableMethodSecurity` |
| S1.3.1 | 模型角色配置 | `resources/application.yml` — `embabel.models` 段 |
| S1.3.2 | LLM 选择服务 | `service/LlmSelectionService.java` |
| S1.4 | H2 数据源配置 | `resources/application.yml` — `spring.datasource` 段 |
