# 阶段一验证文档：数据层与基础设施

> 基于 `spec.md` / `plan.md` / `task.md` 编写，覆盖 MVP1 全量验收点的测试用例、测试步骤、判定标准与回归策略。
> 阶段一交付物：实体建模、种子数据、安全认证、LLM 模型路由、基础配置文件。

---

## 0. 验证范围与非目标

### 0.1 范围（In Scope）

| 模块 | 关键交付 |
|------|---------|
| S1.1 数据初始化 | 6 个 Entity、6 个 Repository、3 个枚举、5 个种子用户 + 5 辆车 |
| S1.2 安全认证 | HTTP Basic、4 角色权限矩阵、角色层级、方法级安全、URL 访问控制 |
| S1.3 LLM 模型路由 | application.yml 角色映射、`LlmSelectionService` 全量方法 |
| S1.4 配置文件 | H2 内存库、ddl-auto=update、OSIV=false、H2 Console |

### 0.2 非目标（Out of Scope）

- Agent 编排逻辑（属于 MVP2/MVP3）
- RAG 检索端到端（属于 MVP3）
- 业务 Controller（属于 MVP2）

---

## 1. 验证总览

### 1.1 测试层级与占比

| 层级 | 类型 | 目标 | 占比 |
|------|------|------|------|
| L1 | 单元测试 | Entity / Enum / Sentinel / LlmSelectionService | 50% |
| L2 | 切片测试 | Repository（@DataJpaTest）、SecurityConfig（@WebMvcTest） | 30% |
| L3 | 集成测试 | DataInitializer + Security + JPA 联合验证 | 15% |
| L4 | 端到端 | 应用启动 + curl 烟测 + Swagger/H2 控制台可达性 | 5% |

### 1.2 通过门槛

| 维度 | 通过标准 |
|------|---------|
| 编译 | `./mvnw compile` 0 错误 0 警告（warning 可豁免第三方） |
| 单元测试 | 行覆盖率 ≥ 80%（Entity/Enum/LlmSelectionService ≥ 95%） |
| 集成测试 | 全部通过，0 跳过 |
| 端到端 | 6 个烟测用例全部通过 |
| 静态检查 | 0 个 critical 级别 checkstyle 违规 |

---

## 2. 验收环境

### 2.1 软件版本

| 组件 | 版本 |
|------|------|
| JDK | 21 |
| Spring Boot | 3.4.0 |
| Maven | 3.9+（项目自带 mvnw） |
| H2 | 随 Spring Boot 默认版本（2.x） |
| DeepSeek API Key | 通过环境变量 `DEEPSEEK_API_KEY` 注入 |

### 2.2 环境变量

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
```

### 2.3 启动命令

```bash
./mvnw spring-boot:run
# 默认端口 8080
```

---

## 3. S1.1 数据初始化 验证

### 3.1 枚举验证（L1 单元测试）

**测试类**：`entity/EnumTest.java`

| 用例编号 | 用例描述 | 输入 | 预期输出 | 断言 |
|---------|---------|------|---------|------|
| ENUM-001 | QuoteStatus 含全部状态 | — | `["PENDING","APPROVED","REFERRED","DECLINED"]` | `values().length == 4` |
| ENUM-002 | PolicyStatus 含全部状态 | — | `["ACTIVE","EXPIRED","CANCELLED","SUSPENDED"]` | `values().length == 4` |
| ENUM-003 | ClaimStatus 含全部状态 | — | `["PENDING","INVESTIGATING","APPROVED","DENIED","PAID"]` | `values().length == 5` |
| ENUM-004 | 枚举 valueOf 双向 | `QuoteStatus.APPROVED` | 名称匹配 | `valueOf("APPROVED") == APPROVED` |

**通过标准**：全部通过。

---

### 3.2 Entity 字段映射验证（L1 单元测试）

**测试类**：`entity/EntityFieldMappingTest.java`

#### 3.2.1 Customer（客户）

| 用例编号 | 字段 | 验证项 | 预期 |
|---------|------|-------|------|
| ENT-CUS-001 | id | 主键自增策略 | `@GeneratedValue(strategy=IDENTITY)` |
| ENT-CUS-002 | userId | 唯一非空 | `@Column(nullable=false, unique=true)` |
| ENT-CUS-003 | name | 非空 | `@Column(nullable=false)` |
| ENT-CUS-004 | dateOfBirth | 非空 LocalDate | `LocalDate` 类型 |
| ENT-CUS-005 | drivingExperienceYears | 非空 int | `int` |
| ENT-CUS-006 | accidentCount | 非空 int | `int` |
| ENT-CUS-007 | email | 非空 | `String` |
| ENT-CUS-008 | phone | 非空 | `String` |
| ENT-CUS-009 | vehicles | 一对多 | `@OneToMany(mappedBy="customer")` |
| ENT-CUS-010 | policies | 一对多 | `@OneToMany(mappedBy="customer")` |
| ENT-CUS-011 | quotes | 一对多 | `@OneToMany(mappedBy="customer")` |

#### 3.2.2 Vehicle（车辆）

| 用例编号 | 字段 | 验证项 | 预期 |
|---------|------|-------|------|
| ENT-VEH-001 | licensePlate | 非空 | `@Column(nullable=false)` |
| ENT-VEH-002 | model | 非空 | `String` |
| ENT-VEH-003 | brand | 非空 | `String` |
| ENT-VEH-004 | year | 列名映射 | `@Column(name="vehicle_year", nullable=false)` |
| ENT-VEH-005 | vehicleValue | 列名映射 | `@Column(name="vehicle_value", nullable=false)` |
| ENT-VEH-006 | customer | 多对一 | `@ManyToOne @JoinColumn(name="customer_id", nullable=false)` |

#### 3.2.3 Quote（报价单）

| 用例编号 | 字段 | 验证项 | 预期 |
|---------|------|-------|------|
| ENT-QTE-001 | customer | 多对一非空 | `@ManyToOne nullable=false` |
| ENT-QTE-002 | vehicle | 多对一非空 | `@ManyToOne nullable=false` |
| ENT-QTE-003 | riskScore | 非空 | `double` |
| ENT-QTE-004 | premiumAmount | 非空 | `double` |
| ENT-QTE-005 | status | 枚举字符串存储 | `@Enumerated(STRING)` |
| ENT-QTE-006 | coverageType | 非空 | `String` |
| ENT-QTE-007 | createdAt | @PrePersist 自动填充 | `LocalDateTime` |
| ENT-QTE-008 | rejectionReason | 可空 | 无 `nullable=false` |
| ENT-QTE-009 | expiresAt | 构造时设值 | `createdAt.plusDays(30)` |

#### 3.2.4 Policy（保单）

| 用例编号 | 字段 | 验证项 | 预期 |
|---------|------|-------|------|
| ENT-POL-001 | policyNumber | 唯一非空 | `@Column(nullable=false, unique=true)` |
| ENT-POL-002 | policyNumber | 格式匹配 | `^POL-\d+-[A-Z]{6}$` |
| ENT-POL-003 | customer | 多对一非空 | `@ManyToOne nullable=false` |
| ENT-POL-004 | vehicle | 多对一非空 | `@ManyToOne nullable=false` |
| ENT-POL-005 | coverageType | 非空 | `String` |
| ENT-POL-006 | premiumAmount | 非空 | `double` |
| ENT-POL-007 | effectiveDate | 非空 | `LocalDateTime` |
| ENT-POL-008 | expirationDate | 非空 | `LocalDateTime` |
| ENT-POL-009 | status | 枚举字符串 | `@Enumerated(STRING)` |
| ENT-POL-010 | claims | 一对多级联 | `@OneToMany(mappedBy="policy", cascade=ALL)` |

#### 3.2.5 Claim（理赔单）

| 用例编号 | 字段 | 验证项 | 预期 |
|---------|------|-------|------|
| ENT-CLM-001 | claimNumber | 唯一非空 | `@Column(nullable=false, unique=true)` |
| ENT-CLM-002 | claimNumber | 格式匹配 | `^CLM-[A-Z]{8}$` |
| ENT-CLM-003 | policy | 多对一非空 | `@ManyToOne nullable=false` |
| ENT-CLM-004 | status | 枚举字符串 | `@Enumerated(STRING)` |
| ENT-CLM-005 | claimedAmount | 非空 | `double` |
| ENT-CLM-006 | paidAmount | 可空 | 无 `nullable=false` |
| ENT-CLM-007 | fraudScore | 非空 | `double` |
| ENT-CLM-008 | description | 长度 2000 | `@Column(length=2000, nullable=false)` |
| ENT-CLM-009 | createdAt | 构造时填充 | `LocalDateTime.now()` |
| ENT-CLM-010 | processId | 可空 | `String` |

#### 3.2.6 PolicyDocument（知识库文档）

| 用例编号 | 字段 | 验证项 | 预期 |
|---------|------|-------|------|
| ENT-DOC-001 | documentName | 非空 | `String` |
| ENT-DOC-002 | content | 长度 5000 | `@Column(length=5000, nullable=false)` |
| ENT-DOC-003 | category | 非空 | `String` |
| ENT-DOC-004 | language | 非空 | `String` |

---

### 3.3 Sentinel 模式验证（L1 单元测试）

**测试类**：`entity/SentinelTest.java`

| 用例编号 | 用例描述 | 输入 | 预期 |
|---------|---------|------|------|
| SEN-001 | Customer.lookupFailed() 创建 sentinel | 调用静态方法 | `userId == "__sentinel__"` |
| SEN-002 | Customer.isLookupFailed(sentinel) | sentinel 对象 | 返回 `true` |
| SEN-003 | Customer.isLookupFailed(正常对象) | 新建 Customer | 返回 `false` |
| SEN-004 | Vehicle.lookupFailed() 创建 sentinel | 调用静态方法 | `licensePlate == "__sentinel__"` |
| SEN-005 | Vehicle.isLookupFailed(sentinel) | sentinel 对象 | 返回 `true` |
| SEN-006 | Vehicle.isLookupFailed(正常对象) | 新建 Vehicle | 返回 `false` |
| SEN-007 | sentinel 不影响常规 CRUD | save & flush | 持久化成功（虽然不推荐） |

**关键断言**：Sentinel 必须能被 `equals` 或属性判定稳定识别，供 Agent UTILITY 规划器做类型槽位占位。

---

### 3.4 派生方法验证（L1 单元测试）

**测试类**：`entity/CustomerAgeTest.java`

| 用例编号 | 出生日期 | 当前日期（mock） | 预期年龄 |
|---------|---------|----------------|---------|
| AGE-001 | 1985-03-15 | 2026-06-23 | 41 |
| AGE-002 | 2000-02-29 | 2026-02-28 | 25（生日未到） |
| AGE-003 | 2000-02-29 | 2026-03-01 | 26（生日已过） |
| AGE-004 | 2000-01-01 | 2026-01-01 | 26（恰好生日） |
| AGE-005 | 2005-12-31 | 2026-01-01 | 20 |

**实现**：

```java
public int getAge() {
    return Period.between(dateOfBirth, LocalDate.now()).getYears();
}
```

---

### 3.5 Repository 验证（L2 切片测试）

**测试类**：`repository/*RepositoryTest.java`，使用 `@DataJpaTest`。

#### 3.5.1 CustomerRepository

| 用例编号 | 方法 | 准备 | 预期 |
|---------|------|------|------|
| REPO-CUS-001 | `findByUserId("user")` | seed user | 返回 Optional 非空，name=John Doe |
| REPO-CUS-002 | `findByUserId("nonexist")` | — | 返回 Optional.empty() |
| REPO-CUS-003 | `findByUserId` 唯一性 | seed 两个同名 userId | 第二个抛 DataIntegrityViolationException |
| REPO-CUS-004 | `save` + `count` | 新增 Customer | count 增加 1 |

#### 3.5.2 VehicleRepository

| 用例编号 | 方法 | 准备 | 预期 |
|---------|------|------|------|
| REPO-VEH-001 | `findByLicensePlate("ABC123")` | seed user/admin 的车 | 返回 Toyota RAV4 |
| REPO-VEH-002 | `findByLicensePlate("ZZZ999")` | — | 返回 Optional.empty() |
| REPO-VEH-003 | `findByCustomerId(user.id)` | seed user | 返回 1 辆车 |
| REPO-VEH-004 | `findByCustomerId(不存在)` | — | 返回空列表 |

#### 3.5.3 PolicyRepository

| 用例编号 | 方法 | 准备 | 预期 |
|---------|------|------|------|
| REPO-POL-001 | `findByPolicyNumber(...)` | new Policy 并 save | 命中唯一编号 |
| REPO-POL-002 | `findByPolicyNumber(...)` 唯一性 | save 两个相同编号 | 抛 DataIntegrityViolationException |
| REPO-POL-003 | `findByCustomerId(...)` | seed 多保单 | 返回客户所有保单 |

#### 3.5.4 ClaimRepository

| 用例编号 | 方法 | 准备 | 预期 |
|---------|------|------|------|
| REPO-CLM-001 | `findByClaimNumber(...)` | new Claim 并 save | 命中唯一编号 |
| REPO-CLM-002 | `findByClaimNumber(...)` 格式校验 | 手动 `save("CLM-abc12345")` | 仍可存（格式仅业务约束） |
| REPO-CLM-003 | `findByPolicyId(...)` | seed 多 Claim | 返回保单全部 Claim |

#### 3.5.5 QuoteRepository / PolicyDocumentRepository

| 用例编号 | 方法 | 准备 | 预期 |
|---------|------|------|------|
| REPO-QTE-001 | `save + findById` | new Quote | 往返一致 |
| REPO-DOC-001 | `save + findAll` | new PolicyDocument | 列表大小增加 |

---

### 3.6 种子数据验证（L3 集成测试）

**测试类**：`config/DataInitializerIntegrationTest.java`，使用 `@SpringBootTest`。

| 用例编号 | 用例描述 | 预期 |
|---------|---------|------|
| SEED-001 | 首次启动 customer count | `== 5` |
| SEED-002 | 首次启动 vehicle count | `== 5` |
| SEED-003 | 二次启动（已存在数据） | `customerRepository.count() != 0` 时跳过 |
| SEED-004 | 低风险用户 userId | 存在且 name=Alice Wang |
| SEED-005 | 中风险用户 userId | 存在且 name=Bob Chen |
| SEED-006 | 高风险用户 userId | 存在且 name=Charlie Zhang |
| SEED-007 | 兼容用户 user/admin | 存在 |
| SEED-008 | 车辆归属 | 每个 Customer 关联一辆 Vehicle |
| SEED-009 | 车牌唯一 | seed 数据中无重复 licensePlate |
| SEED-010 | Alice 年龄派生 | 出生 1985-03-15 → 41 |
| SEED-011 | Alice 驾龄 | `drivingExperienceYears == 15` |
| SEED-012 | Bob 驾龄 | `drivingExperienceYears == 4` |
| SEED-013 | Charlie 驾龄 | `drivingExperienceYears == 1` |

**执行验证**：

```sql
SELECT user_id, name, date_of_birth, driving_experience_years, accident_count FROM customer;
SELECT license_plate, model, brand, vehicle_year, vehicle_value FROM vehicle;
```

---

## 4. S1.2 安全认证 验证

### 4.1 SecurityConfig Bean 装配验证（L2 切片测试）

**测试类**：`config/SecurityConfigTest.java`，使用 `@SpringBootTest(classes = SecurityConfig.class)` 或 `@WebMvcTest`。

| 用例编号 | Bean | 验证项 | 预期 |
|---------|------|-------|------|
| SEC-BEAN-001 | securityFilterChain | 类型 & 非空 | `SecurityFilterChain` 实例 |
| SEC-BEAN-002 | userDetailsService | InMemoryUserDetailsManager | 包含 4 个用户 |
| SEC-BEAN-003 | passwordEncoder | BCryptPasswordEncoder | `BCryptPasswordEncoder` 实例 |
| SEC-BEAN-004 | roleHierarchy | RoleHierarchyImpl | 字符串包含 `ADMIN > UNDERWRITER` |
| SEC-BEAN-005 | methodSecurityExpressionHandler | 设置了 RoleHierarchy | 非空 |
| SEC-BEAN-006 | @Profile 加载 | `prod` profile 下 | SecurityConfig 加载 |
| SEC-BEAN-007 | @Profile 不加载 | `test` profile 下 | SecurityConfig 不加载 |
| SEC-BEAN-008 | @Profile 不加载 | `e2e` profile 下 | SecurityConfig 不加载 |

---

### 4.2 角色与权限矩阵验证（L2 测试）

**测试类**：`config/UserDetailsServiceTest.java`

| 用例编号 | 用户名 | 验证项 | 预期 |
|---------|-------|-------|------|
| SEC-USR-001 | user | 角色 | `ROLE_USER` |
| SEC-USR-002 | user | 权限 | `["underwriting:read","chat:use","policies:read"]` |
| SEC-USR-003 | underwriter | 角色 | `ROLE_UNDERWRITER` |
| SEC-USR-004 | underwriter | 权限 | 包含 `underwriting:write`、`underwriting:approve` |
| SEC-USR-005 | claims | 角色 | `ROLE_CLAIMS` |
| SEC-USR-006 | claims | 权限 | 包含 `claims:write`、`claims:read`、`claims:review` |
| SEC-USR-007 | admin | 角色 | `ROLE_ADMIN` |
| SEC-USR-008 | admin | 权限 | 包含 `rag:admin`、`policies:write`、`chat:admin` |
| SEC-USR-009 | 密码编码 | BCrypt | `{bcrypt}$2a$...` 前缀 |
| SEC-USR-010 | 原始密码 | 编码匹配 | `passwordEncoder.matches("password", user.getPassword()) == true` |

**关键断言**：密码不以明文存储，BCrypt 校验通过。

---

### 4.3 角色层级继承验证（L2 测试）

**测试类**：`config/RoleHierarchyTest.java`

| 用例编号 | 父角色 | 子角色 | 预期（子角色权限对父角色可见） |
|---------|--------|--------|----------------------------|
| ROL-001 | ADMIN | UNDERWRITER | `hasAuthority('underwriting:approve')` 为 true |
| ROL-002 | ADMIN | CLAIMS | `hasAuthority('claims:review')` 为 true |
| ROL-003 | ADMIN | USER | `hasAuthority('chat:use')` 为 true |
| ROL-004 | UNDERWRITER | USER | `hasAuthority('underwriting:read')` 为 true |
| ROL-005 | CLAIMS | USER | `hasAuthority('chat:use')` 为 true |
| ROL-006 | USER | UNDERWRITER | `hasAuthority('underwriting:approve')` 为 **false**（不向上继承） |
| ROL-007 | USER | CLAIMS | `hasAuthority('claims:write')` 为 **false** |
| ROL-008 | UNDERWRITER | CLAIMS | `hasAuthority('claims:write')` 为 **false**（同级不继承） |
| ROL-009 | CLAIMS | UNDERWRITER | `hasAuthority('underwriting:approve')` 为 **false** |

**核心断言**：父角色自动获得子角色全部权限，子角色**不能**自动获得父角色权限。

---

### 4.4 URL 访问控制验证（L3 集成测试 / MockMvc）

**测试类**：`config/UrlAccessControlTest.java`，使用 `@SpringBootTest + @AutoConfigureMockMvc`。

| 用例编号 | URL | HTTP 方法 | 凭证 | 预期状态码 |
|---------|-----|----------|------|----------|
| URL-001 | `/swagger-ui/index.html` | GET | 无 | 200 |
| URL-002 | `/swagger-ui/index.html` | GET | user:password | 200 |
| URL-003 | `/v3/api-docs` | GET | 无 | 200 |
| URL-004 | `/v3/api-docs.yaml` | GET | 无 | 200 |
| URL-005 | `/api/insurance/health` | GET | 无 | 200（permitAll） |
| URL-006 | `/api/insurance/health` | GET | 错误凭证 | 401 |
| URL-007 | `/api/chat/anything` | GET | 无 | 401 |
| URL-008 | `/api/chat/anything` | GET | user:password | 200/4xx（非 401） |
| URL-009 | `/api/insurance/quotes` | POST | 无 | 401 |
| URL-010 | `/api/insurance/quotes` | POST | user:password | 200/4xx（视具体实现） |
| URL-011 | 任何 `/api/**` | GET | user:wrong | 401 |
| URL-012 | CSRF POST | POST | user:password | 不报 CSRF 错误（已禁用） |

---

### 4.5 方法级安全验证（L3 集成测试）

**测试类**：随对应 Controller 测试，本阶段尚无业务 Controller，仅验证 Bean 装配。

| 用例编号 | 验证项 | 预期 |
|---------|-------|------|
| SEC-MTH-001 | `@EnableMethodSecurity(prePostEnabled=true)` 启用 | 注解存在 |
| SEC-MTH-002 | `@PreAuthorize("hasAuthority('xxx')")` 解析 | 编译期 BeanPostProcessor 注册 |

> 注：MVP1 不强制要求业务 Controller，但 SecurityConfig 必须为后续阶段预留 `@PreAuthorize` 能力。

---

### 4.6 端到端认证烟测（L4）

**前置**：应用以默认 profile 启动（不激活 test/e2e）。

| 用例编号 | 命令 | 预期 |
|---------|------|------|
| E2E-SEC-001 | `curl -i http://localhost:8080/api/insurance/health` | `HTTP/1.1 200 OK` |
| E2E-SEC-002 | `curl -i -u user:password http://localhost:8080/api/insurance/health` | `200 OK` |
| E2E-SEC-003 | `curl -i -u user:wrong http://localhost:8080/api/insurance/health` | `401 Unauthorized`，`WWW-Authenticate: Basic` |
| E2E-SEC-004 | `curl -i http://localhost:8080/swagger-ui/index.html` | `302` 重定向到 `/swagger-ui/index.html` 后 `200` |
| E2E-SEC-005 | `curl -i -u admin:admin http://localhost:8080/h2-console` | 跳转登录页 `200` |
| E2E-SEC-006 | 浏览器访问 `http://localhost:8080/swagger-ui/index.html` | 渲染 OpenAPI UI |

---

## 5. S1.3 LLM 模型路由 验证

### 5.1 application.yml 模型映射验证（L2 测试）

**测试类**：`config/EmbabelModelConfigTest.java`，通过 `@ConfigurationProperties` 或直接加载 YAML。

| 用例编号 | 配置项 | 预期 |
|---------|-------|------|
| LLM-CFG-001 | `embabel.models.default-llm` | `deepseek-chat` |
| LLM-CFG-002 | `embabel.models.llms.fast` | `deepseek-chat` |
| LLM-CFG-003 | `embabel.models.llms.balanced` | `deepseek-chat` |
| LLM-CFG-004 | `embabel.models.llms.powerful` | `deepseek-reasoner` |
| LLM-CFG-005 | `embabel.models.llms.best` | `deepseek-reasoner` |
| LLM-CFG-006 | `embabel.agent.platform.models.deepseek.api-key` | `${DEEPSEEK_API_KEY}` 解析成功 |

---

### 5.2 LlmSelectionService 方法映射验证（L1 单元测试）

**测试类**：`service/LlmSelectionServiceTest.java`，Mock Embabel `LlmOptions`。

| 用例编号 | 方法 | 验证手段 | 预期角色 |
|---------|------|---------|---------|
| LLM-SVC-001 | `forSimpleQuery()` | 捕获 `LlmOptions.getRole()` | `fast` |
| LLM-SVC-002 | `forRetrieval()` | 同上 | `fast` |
| LLM-SVC-003 | `forSummarization()` | 同上 | `balanced` |
| LLM-SVC-004 | `forComplexReasoning()` | 同上 | `powerful` |
| LLM-SVC-005 | `forUnderwriting()` | 同上 | `balanced` |
| LLM-SVC-006 | `forClaims()` | 同上 | `balanced` |
| LLM-SVC-007 | `forChat()` | 同上 | `balanced` |
| LLM-SVC-008 | `forEmbedding()` | 同上 | `embedding` |
| LLM-SVC-009 | `forAuto()` | 验证 `withAutoLlm()` 被调用 | `auto` |
| LLM-SVC-010 | `forModel("gpt-4")` | 验证 model 名透传 | `gpt-4` |
| LLM-SVC-011 | `forComplexity(10)` | 按分数映射 | `fast` |
| LLM-SVC-012 | `forComplexity(45)` | 同上 | `balanced` |
| LLM-SVC-013 | `forComplexity(85)` | 同上 | `powerful` |
| LLM-SVC-014 | `forComplexity(0)` | 边界值 | `fast` |
| LLM-SVC-015 | `forComplexity(30)` | 边界值 | `fast` |
| LLM-SVC-016 | `forComplexity(31)` | 边界值 | `balanced` |
| LLM-SVC-017 | `forComplexity(60)` | 边界值 | `balanced` |
| LLM-SVC-018 | `forComplexity(61)` | 边界值 | `powerful` |
| LLM-SVC-019 | `forComplexity(100)` | 边界值 | `powerful` |

**核心断言**：分数映射区间为 `[0,30]=fast`、`[31,60]=balanced`、`[61,100]=powerful`。

---

### 5.3 角色常量验证（L1）

| 用例编号 | 常量 | 预期值 |
|---------|------|--------|
| LLM-CONST-001 | `ROLE_FAST` | `"fast"` |
| LLM-CONST-002 | `ROLE_BALANCED` | `"balanced"` |
| LLM-CONST-003 | `ROLE_POWERFUL` | `"powerful"` |
| LLM-CONST-004 | `ROLE_EMBEDDING` | `"embedding"` |

---

### 5.4 端到端 LLM 调用（L4，集成测试中可选）

| 用例编号 | 用例 | 预期 |
|---------|------|------|
| LLM-E2E-001 | 真实环境：调用 `forSimpleQuery()` 并发起一次最简 LLM 请求 | 200 OK（需要真实 API key） |
| LLM-E2E-002 | 环境变量缺失 | 启动时日志告警，调用时降级或失败但不应崩溃 |

> 仅在 CI 提供 DEEPSEEK_API_KEY 时执行；否则跳过并标注 SKIP。

---

## 6. S1.4 配置文件 验证

### 6.1 application.yml 字段验证（L2 测试）

**测试类**：`config/ApplicationYamlTest.java`，使用 `@SpringBootTest` 注入 `Environment`。

| 用例编号 | 属性 | 预期 |
|---------|------|------|
| YAML-001 | `spring.application.name` | `fast-claim-agent-api` |
| YAML-002 | `spring.datasource.url` | `jdbc:h2:mem:claimdb;...` |
| YAML-003 | `spring.datasource.driver-class-name` | `org.h2.Driver` |
| YAML-004 | `spring.datasource.username` | `sa` |
| YAML-005 | `spring.jpa.hibernate.ddl-auto` | `update` |
| YAML-006 | `spring.jpa.open-in-view` | `false` |
| YAML-007 | `spring.h2.console.enabled` | `true` |
| YAML-008 | `spring.h2.console.path` | `/h2-console` |
| YAML-009 | `server.port` | `8080` |
| YAML-010 | `claim.rag.documents-path` | `classpath:documents/` |
| YAML-011 | `claim.rag.documents` | `claims_guide.md,faq.md` |
| YAML-012 | `claim.rag.auto-ingest` | `true` |
| YAML-013 | `claim.rag.lucene.name` | `claim-lucene` |
| YAML-014 | `claim.rag.lucene.chunk-size` | `1000` |
| YAML-015 | `claim.rag.lucene.chunk-overlap` | `200` |
| YAML-016 | `logging.level.com.fastclaim` | `DEBUG` |

---

### 6.2 H2 表结构验证（L3 集成测试）

应用启动后通过 H2 Console 或 JDBC URL 执行：

```sql
SHOW TABLES;
```

| 用例编号 | 期望表 | 关键列 |
|---------|--------|--------|
| TBL-001 | CUSTOMER | id, user_id(UK), name, date_of_birth, ... |
| TBL-002 | VEHICLE | id, license_plate, vehicle_year, vehicle_value, customer_id(FK) |
| TBL-003 | QUOTE | id, customer_id, vehicle_id, risk_score, status, ... |
| TBL-004 | POLICY | id, policy_number(UK), customer_id, vehicle_id, ... |
| TBL-005 | CLAIM | id, claim_number(UK), policy_id, status, ... |
| TBL-006 | POLICY_DOCUMENT | id, document_name, content, category, language |

**关键列名映射校验**：`vehicle_year` 与 `vehicle_value` 区分大小写。

---

### 6.3 H2 Console 可达性（L4）

| 用例编号 | 操作 | 预期 |
|---------|------|------|
| H2-001 | 浏览器访问 `http://localhost:8080/h2-console` | 登录页面 |
| H2-002 | JDBC URL 填 `jdbc:h2:mem:claimdb`，user=`sa`，password=`""` | 连接成功 |
| H2-003 | 执行 `SELECT COUNT(*) FROM CUSTOMER` | 返回 5 |
| H2-004 | 执行 `SELECT COUNT(*) FROM VEHICLE` | 返回 5 |

---

## 7. 端到端综合验证

### 7.1 启动日志检查

```bash
./mvnw spring-boot:run 2>&1 | tee startup.log
```

**必须出现的关键字**：

| 用例编号 | 日志关键字 | 说明 |
|---------|----------|------|
| BOOT-001 | `Started FastClaimAgentApiApplication` | 启动成功 |
| BOOT-002 | `DataInitializer` 或种子数据加载相关日志 | 种子执行 |
| BOOT-003 | `Tomcat started on port 8080` | Web 容器启动 |
| BOOT-004 | `H2 console available at '/h2-console'` | H2 控制台可用 |
| BOOT-005 | `Embabel Agent Platform` 相关启动日志 | 智能体平台就绪 |
| BOOT-006 | 无 `ERROR` 级别日志（允许 WARN） | 启动无致命错误 |

---

### 7.2 综合烟测矩阵（L4）

| 用例编号 | 场景 | 操作 | 预期 |
|---------|------|------|------|
| SMOKE-001 | 健康检查无认证 | `curl http://localhost:8080/api/insurance/health` | 200 |
| SMOKE-002 | 健康检查带认证 | `curl -u user:password http://localhost:8080/api/insurance/health` | 200 |
| SMOKE-003 | Swagger 可访问 | 浏览器 `/swagger-ui/index.html` | 200 |
| SMOKE-004 | OpenAPI JSON | `curl http://localhost:8080/v3/api-docs` | 200，JSON |
| SMOKE-005 | H2 Console 可达 | 浏览器 `/h2-console` | 200 |
| SMOKE-006 | 用户密码错误 | `curl -u user:wrong http://localhost:8080/api/insurance/health` | 401 |
| SMOKE-007 | 无 Authorization | `curl http://localhost:8080/api/chat/test` | 401 |
| SMOKE-008 | Actuator/未知端点 | `curl http://localhost:8080/nonexistent` | 401 或 404 |

---

## 8. 验收检查清单

### 8.1 数据层（S1.1）

- [ ] 6 个实体类（含 `@Entity`、`@Table`）均已创建
- [ ] 6 个 Repository 接口均已创建，自定义查询方法签名匹配
- [ ] 3 个枚举类（QuoteStatus / PolicyStatus / ClaimStatus）值集合匹配
- [ ] Customer / Vehicle 提供 `lookupFailed()` 与 `isLookupFailed()` 静态方法
- [ ] Customer.getAge() 按 `Period.between` 实现
- [ ] DataInitializer 仅在数据库为空时执行
- [ ] 种子数据包含 5 个用户 + 5 辆车，覆盖三种风险等级

### 8.2 安全（S1.2）

- [ ] SecurityConfig 仅在非 test/e2e profile 下加载
- [ ] 4 个内置用户（user / underwriter / claims / admin）均可认证
- [ ] 密码经 BCrypt 编码存储
- [ ] 角色层级 ADMIN > UNDERWRITER/CLAIMS > USER 生效
- [ ] `/swagger-ui/**`、`/v3/api-docs/**`、`/api/insurance/health` permitAll
- [ ] 其余 `/api/**` 必须认证
- [ ] `@EnableMethodSecurity(prePostEnabled=true)` 启用

### 8.3 LLM 路由（S1.3）

- [ ] application.yml 中 4 个角色映射正确
- [ ] `DEEPSEEK_API_KEY` 环境变量已配置
- [ ] LlmSelectionService 11 个方法（含 `forComplexity`）全部实现
- [ ] 角色常量与映射一致

### 8.4 配置（S1.4）

- [ ] H2 内存数据库可连接
- [ ] ddl-auto=update 生效，所有表自动创建
- [ ] open-in-view=false 生效
- [ ] H2 Console 在 `/h2-console` 可访问

---

## 9. 回归策略

### 9.1 触发回归的场景

| 场景 | 回归范围 |
|------|---------|
| 实体字段增删 | L1 Entity 测试 + L2 Repository 测试 + L3 表结构验证 |
| 新增角色 | L2 SecurityConfig 测试 + L2 角色层级测试 |
| 调整 LLM 角色映射 | L2 模型配置测试 + L1 LlmSelectionService 测试 |
| 修改种子数据 | L3 DataInitializer 测试 + L4 启动日志 |
| application.yml 变更 | L2 YAML 字段测试 |

### 9.2 测试数据隔离

- L1：纯单元测试，无需数据库
- L2：使用 `@DataJpaTest` + H2 内存库 `@AutoConfigureTestDatabase(replace=ANY)`
- L3：使用 `@SpringBootTest`，每个测试方法前 `@Transactional` 回滚或 `@DirtiesContext` 重建上下文

### 9.3 CI 集成

```yaml
# .github/workflows/mvp1-verify.yml (示例)
name: MVP1 Verify
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21' }
      - run: ./mvnw -B verify
        env:
          DEEPSEEK_API_KEY: ${{ secrets.DEEPSEEK_API_KEY }}
```

---

## 10. 风险与已知限制

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| DEEPSEEK_API_KEY 缺失 | 中 | 仅在 `LLM-E2E-*` 用例要求；其他测试不依赖 |
| H2 内存库重启清空 | 低 | MVP 阶段可接受；阶段二起规划 PostgreSQL 切换 |
| InMemoryUserDetailsManager | 中 | 仅适用于开发/演示；生产需替换为 JDBC/JPA UserDetailsService |
| `ddl-auto=update` 不支持 destructive 变更 | 低 | 字段重命名需手动迁移；阶段二引入 Flyway/Liquibase |
| Sentinel 对象若被 save 到 DB | 低 | 业务层需在持久化前过滤；可补充校验用例 |

---

## 11. 签字栏

| 角色 | 姓名 | 签字 | 日期 |
|------|------|------|------|
| 开发 |  |  |  |
| 测试 |  |  |  |
| 产品 |  |  |  |
| 架构 |  |  |  |

---

## 附录 A：测试目录建议

```
src/test/java/com/fastclaim/
├── entity/
│   ├── EnumTest.java
│   ├── EntityFieldMappingTest.java
│   ├── SentinelTest.java
│   └── CustomerAgeTest.java
├── repository/
│   ├── CustomerRepositoryTest.java
│   ├── VehicleRepositoryTest.java
│   ├── PolicyRepositoryTest.java
│   ├── ClaimRepositoryTest.java
│   ├── QuoteRepositoryTest.java
│   └── PolicyDocumentRepositoryTest.java
├── config/
│   ├── SecurityConfigTest.java
│   ├── UserDetailsServiceTest.java
│   ├── RoleHierarchyTest.java
│   ├── UrlAccessControlTest.java
│   ├── DataInitializerIntegrationTest.java
│   ├── EmbabelModelConfigTest.java
│   └── ApplicationYamlTest.java
└── service/
    └── LlmSelectionServiceTest.java
```

## 附录 B：种子数据预期查询结果

```sql
-- 期望返回 5 行
SELECT user_id, name, driving_experience_years, accident_count
FROM customer
ORDER BY id;

-- 期望返回 5 行
SELECT license_plate, model, brand, vehicle_year, vehicle_value
FROM vehicle
ORDER BY id;
```

| user_id | name | driving_experience_years | accident_count |
|---------|------|---------------------------|----------------|
| low-risk-user | Alice Wang | 15 | 1 |
| medium-risk-user | Bob Chen | 4 | 2 |
| high-risk-user | Charlie Zhang | 1 | 3 |
| user | John Doe | 15 | 2 |
| admin | Jane Smith | 8 | 0 |

| license_plate | model | brand | vehicle_year | vehicle_value |
|---------------|-------|-------|--------------|---------------|
| LOW001 | RAV4 | Toyota | 2022 | 300000 |
| MED001 | Civic | Honda | 2018 | 180000 |
| HIGH001 | X5 | BMW | 2013 | 600000 |
| ABC123 | RAV4 | Toyota | 2020 | 250000 |
| XYZ789 | Model 3 | Tesla | 2022 | 450000 |
