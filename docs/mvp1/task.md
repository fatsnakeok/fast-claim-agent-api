# 阶段一详细设计任务文档：数据层与基础设施

> 基于 `spec1.md` 和 `plan1.md`，逐任务分解实现细节。

---

## 任务总览

| 编号 | 任务 | 产出路径 | 优先级 |
|------|------|---------|--------|
| T1 | 枚举类定义 | `entity/` | P0（依赖 T2） |
| T2 | 实体建模 | `entity/` | P0 |
| T3 | Repository 接口 | `repository/` | P0 |
| T4 | 种子数据初始化 | `config/DataInitializer.java` | P1 |
| T5 | 安全配置 | `config/SecurityConfig.java` | P1 |
| T6 | LLM 模型选择服务 | `service/LlmSelectionService.java` | P1 |
| T7 | 应用配置更新 | `resources/application.yml` | P1 |

**依赖关系**：T1 → T2 → T3，其他任务依赖 T3 完成后可并行。

---

## T1 枚举类定义

### T1.1 QuoteStatus — 报价单状态

**路径**：`src/main/java/com/fastclaim/entity/QuoteStatus.java`

```java
package com.fastclaim.entity;

public enum QuoteStatus {
    PENDING,    // 待处理
    APPROVED,   // 已批准
    REFERRED,   // 转人工
    DECLINED    // 已拒绝
}
```

### T1.2 PolicyStatus — 保单状态

**路径**：`src/main/java/com/fastclaim/entity/PolicyStatus.java`

```java
package com.fastclaim.entity;

public enum PolicyStatus {
    ACTIVE,     // 有效
    EXPIRED,    // 已到期
    CANCELLED,  // 已取消
    SUSPENDED   // 已暂停
}
```

### T1.3 ClaimStatus — 理赔单状态

**路径**：`src/main/java/com/fastclaim/entity/ClaimStatus.java`

```java
package com.fastclaim.entity;

public enum ClaimStatus {
    PENDING,        // 待处理
    INVESTIGATING,  // 调查中
    APPROVED,       // 已批准
    DENIED,         // 已拒绝
    PAID            // 已赔付
}
```

---

## T2 实体建模

### T2.1 Customer（客户）

**路径**：`src/main/java/com/fastclaim/entity/Customer.java`

| 字段 | 类型 | JPA 约束 |
|------|------|---------|
| id | Long | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| userId | String | `@Column(nullable=false, unique=true)` |
| name | String | `@Column(nullable=false)` |
| dateOfBirth | LocalDate | `@Column(nullable=false)` |
| drivingExperienceYears | int | `@Column(nullable=false)` |
| accidentCount | int | `@Column(nullable=false)` |
| email | String | `@Column(nullable=false)` |
| phone | String | `@Column(nullable=false)` |

**关联**：
- `@OneToMany(mappedBy="customer")` → `List<Vehicle> vehicles`
- `@OneToMany(mappedBy="customer")` → `List<Policy> policies`
- `@OneToMany(mappedBy="customer")` → `List<Quote> quotes`

**派生方法**：
- `getAge()` — `Period.between(dateOfBirth, LocalDate.now()).getYears()`

**静态工厂**：
- `Customer.lookupFailed()` — 返回 `userId="__sentinel__"` 的占位对象
- `Customer.isLookupFailed(Customer c)` — `"__sentinel__".equals(c.getUserId())`

**JPA 注解**：`@Entity`, `@Table(name="customer")`

---

### T2.2 Vehicle（车辆）

**路径**：`src/main/java/com/fastclaim/entity/Vehicle.java`

| 字段 | 类型 | JPA 约束 |
|------|------|---------|
| id | Long | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| licensePlate | String | `@Column(nullable=false)` |
| model | String | `@Column(nullable=false)` |
| brand | String | `@Column(nullable=false)` |
| year | int | `@Column(name="vehicle_year", nullable=false)` |
| vehicleValue | double | `@Column(name="vehicle_value", nullable=false)` |
| customer | Customer | `@ManyToOne`, `@JoinColumn(name="customer_id", nullable=false)` |

**静态工厂**：
- `Vehicle.lookupFailed()` — 返回 `licensePlate="__sentinel__"` 的占位对象
- `Vehicle.isLookupFailed(Vehicle v)` — `"__sentinel__".equals(v.getLicensePlate())`

---

### T2.3 Quote（报价单）

**路径**：`src/main/java/com/fastclaim/entity/Quote.java`

| 字段 | 类型 | JPA 约束 |
|------|------|---------|
| id | Long | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| customer | Customer | `@ManyToOne`, `@JoinColumn(name="customer_id", nullable=false)` |
| vehicle | Vehicle | `@ManyToOne`, `@JoinColumn(name="vehicle_id", nullable=false)` |
| riskScore | double | `@Column(nullable=false)` |
| premiumAmount | double | `@Column(nullable=false)` |
| status | QuoteStatus | `@Enumerated(STRING)`, `@Column(nullable=false)` |
| coverageType | String | `@Column(nullable=false)` |
| createdAt | LocalDateTime | `@Column(nullable=false)`, `@PrePersist` 设 `LocalDateTime.now()` |
| rejectionReason | String | 可空 |
| expiresAt | LocalDateTime | 构造函数中设 `createdAt.plusDays(30)` |

---

### T2.4 Policy（保单）

**路径**：`src/main/java/com/fastclaim/entity/Policy.java`

| 字段 | 类型 | JPA 约束 |
|------|------|---------|
| id | Long | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| policyNumber | String | `@Column(nullable=false, unique=true)` |
| customer | Customer | `@ManyToOne`, `@JoinColumn(name="customer_id", nullable=false)` |
| vehicle | Vehicle | `@ManyToOne`, `@JoinColumn(name="vehicle_id", nullable=false)` |
| coverageType | String | `@Column(nullable=false)` |
| premiumAmount | double | `@Column(nullable=false)` |
| effectiveDate | LocalDateTime | `@Column(nullable=false)` |
| expirationDate | LocalDateTime | `@Column(nullable=false)` |
| status | PolicyStatus | `@Enumerated(STRING)`, `@Column(nullable=false)` |

**关联**：`@OneToMany(mappedBy="policy", cascade=ALL)` → `List<Claim> claims`

**编号生成**：`POL-{System.currentTimeMillis()}-{6位随机大写字母}`

---

### T2.5 Claim（理赔单）

**路径**：`src/main/java/com/fastclaim/entity/Claim.java`

| 字段 | 类型 | JPA 约束 |
|------|------|---------|
| id | Long | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| claimNumber | String | `@Column(nullable=false, unique=true)` |
| policy | Policy | `@ManyToOne`, `@JoinColumn(name="policy_id", nullable=false)` |
| status | ClaimStatus | `@Enumerated(STRING)`, `@Column(nullable=false)` |
| claimedAmount | double | `@Column(nullable=false)` |
| paidAmount | double | 可空 |
| fraudScore | double | `@Column(nullable=false)` |
| description | String | `@Column(length=2000, nullable=false)` |
| createdAt | LocalDateTime | 构造函数中设 `LocalDateTime.now()` |
| processId | String | 可空 |

**编号生成**：`CLM-{8位随机大写字母}`

---

### T2.6 PolicyDocument（知识库文档）

**路径**：`src/main/java/com/fastclaim/entity/PolicyDocument.java`

| 字段 | 类型 | JPA 约束 |
|------|------|---------|
| id | Long | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| documentName | String | `@Column(nullable=false)` |
| content | String | `@Column(length=5000, nullable=false)` |
| category | String | `@Column(nullable=false)` |
| language | String | `@Column(nullable=false)` |

---

## T3 Repository 接口

| 接口 | 路径 | 继承 | 自定义方法 |
|------|------|------|-----------|
| CustomerRepository | `repository/CustomerRepository.java` | `JpaRepository<Customer, Long>` | `Optional<Customer> findByUserId(String userId)` |
| VehicleRepository | `repository/VehicleRepository.java` | `JpaRepository<Vehicle, Long>` | `Optional<Vehicle> findByLicensePlate(String plate)`, `List<Vehicle> findByCustomerId(Long customerId)` |
| QuoteRepository | `repository/QuoteRepository.java` | `JpaRepository<Quote, Long>` | 无（继承 CRUD） |
| PolicyRepository | `repository/PolicyRepository.java` | `JpaRepository<Policy, Long>` | `Optional<Policy> findByPolicyNumber(String no)`, `List<Policy> findByCustomerId(Long customerId)` |
| ClaimRepository | `repository/ClaimRepository.java` | `JpaRepository<Claim, Long>` | `Optional<Claim> findByClaimNumber(String no)`, `List<Claim> findByPolicyId(Long policyId)` |
| PolicyDocumentRepository | `repository/PolicyDocumentRepository.java` | `JpaRepository<PolicyDocument, Long>` | 无（继承 CRUD） |

所有接口标注 `@Repository`，无需实现类。

---

## T4 种子数据初始化

**路径**：`src/main/java/com/fastclaim/config/DataInitializer.java`

**实现**：`implements CommandLineRunner`

**注入**：`CustomerRepository`, `VehicleRepository`

**初始化条件**：`customerRepository.count() == 0`

**种子数据**：

| userId | name | 出生日期 | 驾龄 | 事故 | 车型 | 年份 | 车价(¥) | 车牌 | riskScore | 覆盖场景 |
|--------|------|---------|------|------|------|------|---------|------|-----------|---------|
| low-risk-user | Alice Wang | 1985-03-15 | 15 | 1 | Toyota RAV4 | 2022 | 300k | LOW001 | 15 | 低风险自动批准 |
| medium-risk-user | Bob Chen | 1999-07-20 | 4 | 2 | Honda Civic | 2018 | 180k | MED001 | 63 | 中风险转人工 |
| high-risk-user | Charlie Zhang | 2005-01-10 | 1 | 3 | BMW X5 | 2013 | 600k | HIGH001 | 100 | 高风险拒绝 |
| user | John Doe | 1985-05-15 | 15 | 2 | Toyota RAV4 | 2020 | 250k | ABC123 | — | 向后兼容 |
| admin | Jane Smith | 1990-10-20 | 8 | 0 | Tesla Model 3 | 2022 | 450k | XYZ789 | — | 向后兼容 |

**逻辑**：每个用户 new Customer → `customerRepository.save()` → new Vehicle(关联 customer) → `vehicleRepository.save()`

---

## T5 安全配置

**路径**：`src/main/java/com/fastclaim/config/SecurityConfig.java`

**注解**：
- `@Configuration`
- `@EnableWebSecurity`
- `@EnableMethodSecurity(prePostEnabled = true)`
- `@Profile("!test & !e2e")`

**Bean 清单**：

### 5.1 SecurityFilterChain
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
            .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
            .requestMatchers("/api/insurance/health").permitAll()
            .anyRequest().authenticated()
        )
        .httpBasic(Customizer.withDefaults());
    return http.build();
}
```

### 5.2 UserDetailsService
四用户（密码均为原始值，由 PasswordEncoder 编码）：
| 用户名 | 原始密码 | 角色 |
|--------|---------|------|
| user | password | USER |
| underwriter | underwriter | UNDERWRITER |
| claims | claims | CLAIMS |
| admin | admin | ADMIN |

权限明细：
- USER: `underwriting:read`, `chat:use`, `policies:read`
- UNDERWRITER: 继承 USER + `underwriting:write`, `underwriting:approve`
- CLAIMS: 继承 USER + `claims:write`, `claims:read`, `claims:review`
- ADMIN: 全部 + `rag:admin`, `policies:write`, `chat:admin`

每个用户通过 `User.withUsername().password(passwordEncoder.encode(...)).roles("XXX")` 构建，authorities 通过 `.authorities(...)` 直接设置。

### 5.3 PasswordEncoder
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

### 5.4 RoleHierarchy
```java
@Bean
public RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy(
        "ADMIN > UNDERWRITER\n" +
        "ADMIN > CLAIMS\n" +
        "UNDERWRITER > USER\n" +
        "CLAIMS > USER"
    );
}
```

### 5.5 MethodSecurityExpressionHandler（可选）
如果需要将 RoleHierarchy 接入 `@PreAuthorize`：
```java
@Bean
static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy rh) {
    DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
    handler.setRoleHierarchy(rh);
    return handler;
}
```

---

## T6 LLM 模型选择服务

**路径**：`src/main/java/com/fastclaim/service/LlmSelectionService.java`

**注解**：`@Service`

**角色常量**：
```java
public static final String ROLE_FAST      = "fast";
public static final String ROLE_BALANCED  = "balanced";
public static final String ROLE_POWERFUL  = "powerful";
public static final String ROLE_EMBEDDING = "embedding";
```

**方法清单**：

| 方法 | 返回值 | 模型角色 | 说明 |
|------|--------|---------|------|
| `forSimpleQuery()` | `LlmOptions` | fast | 简单查询 |
| `forRetrieval()` | `LlmOptions` | fast | RAG 检索 |
| `forSummarization()` | `LlmOptions` | balanced | 文档摘要 |
| `forComplexReasoning()` | `LlmOptions` | powerful | 复杂推理 |
| `forUnderwriting()` | `LlmOptions` | balanced | 核保决策 |
| `forClaims()` | `LlmOptions` | balanced | 理赔处理 |
| `forChat()` | `LlmOptions` | balanced | 通用客服 |
| `forEmbedding()` | `LlmOptions` | embedding | 嵌入操作（预留） |
| `forAuto()` | `LlmOptions` | auto | 框架自动选择 |
| `forModel(String modelName)` | `LlmOptions` | — | 按模型名指定 |
| `forComplexity(int score)` | `LlmOptions` | 按分数 | 0-30→fast, 31-60→balanced, 61-100→powerful |

**实现方式**：每个方法调用 `LlmOptions.withLlmForRole(role)` 或 `LlmOptions.withAutoLlm()` 返回。`forModel()` 使用 `LlmOptions.builder().model(modelName).build()`。`forComplexity()` 内部按分数映射到对应角色后再调用 `withLlmForRole`。

---

## T7 应用配置

**路径**：`src/main/resources/application.yml`

当前已有基础配置，需确认以下段完整：

```yaml
spring:
  application:
    name: fast-claim-agent-api
  datasource:
    url: jdbc:h2:mem:claimdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  h2:
    console:
      enabled: true
      path: /h2-console

embabel:
  models:
    default-llm: deepseek-chat
    llms:
      fast: deepseek-chat
      balanced: deepseek-chat
      powerful: deepseek-reasoner
      best: deepseek-reasoner
  agent:
    platform:
      models:
        deepseek:
          api-key: ${DEEPSEEK_API_KEY}
    agent-platform:
      scanning:
        annotation: true
        bean: true
  observability:
    enabled: false

claim:
  rag:
    documents-path: classpath:documents/
    documents: claims_guide.md,faq.md
    auto-ingest: true
    lucene:
      name: claim-lucene
      chunk-size: 1000
      chunk-overlap: 200

server:
  port: 8080

logging:
  level:
    com.fastclaim: DEBUG
    com.embabel.agent: DEBUG
```

> 当前 `application.yml` 已包含上述配置，T7 仅需确认并补充缺失项（如有）。

---

## 实施顺序

```
T1 (枚举) ──▶ T2 (实体) ──▶ T3 (Repository)
                                │
                    ┌───────────┼───────────┐
                    ▼           ▼           ▼
                  T4 (种子)   T5 (安全)   T6 (LLM) + T7 (配置)
```

1. **先 T1**：枚举无外部依赖，先建好供实体引用
2. **再 T2**：实体依赖枚举，JPA 映射是数据层核心
3. **再 T3**：Repository 依赖实体类型，T2 完成后全部接口可一次写出
4. **最后 T4-T7 并行**：安全、种子数据、LLM 服务、配置各自独立

---

## 验证标准

| 验证项 | 方法 |
|--------|------|
| 编译通过 | `./mvnw compile` |
| 实体自动建表 | 启动应用，H2 Console 查看表结构 |
| 种子数据加载 | 启动日志确认 `DataInitializer` 执行，H2 查询 `SELECT * FROM customer` |
| 安全认证 | `curl -u user:password http://localhost:8080/api/insurance/health` 返回 200 |
| 无认证访问 | `curl http://localhost:8080/api/insurance/health` 返回 200（health 端点） |
| 认证失败 | `curl -u user:wrong http://localhost:8080/api/chat/xxx` 返回 401 |
| Swagger UI | 浏览器访问 `http://localhost:8080/swagger-ui/index.html` 可打开 |
| H2 Console | 浏览器访问 `http://localhost:8080/h2-console` 可登录 |
