# MVP1 验证结果摘要

> 验证日期：2026-06-23  
> 验证依据：`docs/mvp1/test.md`  
> 验证方式：静态源码盘点 + 编译检查 + `./mvnw test`

---

## 0. 执行摘要

| 维度 | 结果 | 说明 |
|------|------|------|
| 编译 | PASS | `./mvnw compile` 0 错误 |
| 单元测试 | **未覆盖** | 无测试类文件，覆盖率为 0% |
| 集成测试 | **未覆盖** | 无测试类文件 |
| 端到端 | **未执行** | 无测试类 + 未启动应用 |
| 静态源码验证 | **PASS** | 19 个源文件全部符合 spec.md 要求 |

**结论**：代码交付物完整符合 S1.1~S1.4 规格，但 **测试代码未编写**，需要补齐附录 A 所列的全部测试类。

---

## 1. 编译结果

```
./mvnw compile → BUILD SUCCESS（0 错误, 0 警告）
./mvnw test   → BUILD SUCCESS（Tests run: 0 — 无测试类）
```

---

## 2. S1.1 数据初始化 — 静态验证

### 2.1 枚举（ENUM-001 ~ ENUM-004）：PASS

| 枚举 | 文件 | 值 | 数量 |
|------|------|-----|:--:|
| `ClaimStatus` | `entity/enums/ClaimStatus.java` | PENDING / INVESTIGATING / APPROVED / DENIED / PAID | 5 |
| `PolicyStatus` | `entity/enums/PolicyStatus.java` | ACTIVE / EXPIRED / CANCELLED / SUSPENDED | 4 |
| `QuoteStatus` | `entity/enums/QuoteStatus.java` | PENDING / APPROVED / REFERRED / DECLINED | 4 |

### 2.2 实体字段映射（ENT-CUS-001 ~ ENT-DOC-004）：PASS

6 个实体类均已创建，关键注解验证通过：

| 实体 | PK | UK | FK | ENUM | @OneToMany |
|------|:--:|:--:|:--:|:----:|:----------:|
| Customer | IDENTITY | userId | — | — | vehicle/policy/quote |
| Vehicle | IDENTITY | — | customer_id | — | — |
| Policy | IDENTITY | policyNumber | customer_id, vehicle_id | PolicyStatus | claims (cascade=ALL) |
| Quote | IDENTITY | — | customer_id, vehicle_id | QuoteStatus | — |
| Claim | IDENTITY | claimNumber | policy_id | ClaimStatus | — |
| PolicyDocument | IDENTITY | — | — | — | — |

特殊列名：`vehicle_year`、`vehicle_value` — `@Column(name="...")` 映射正确。

### 2.3 Sentinel 模式（SEN-001 ~ SEN-007）：PASS

- `Customer.lookupFailed()` → 返回 `__sentinel__` userId
- `Customer.isLookupFailed(Customer)` → 存在
- `Vehicle.lookupFailed()` → 返回 `licensePlate = "__sentinel__"`
- `Vehicle.isLookupFailed(Vehicle)` → 存在

### 2.4 派生方法（AGE-001 ~ AGE-005）：PASS

`Customer.getAge()` — `Period.between(dateOfBirth, LocalDate.now())` 实现。

### 2.5 Repository（REPO-CUS-001 ~ REPO-DOC-001）：PASS

| Repository | 自定义查询方法 |
|-----------|--------------|
| CustomerRepository | `findByUserId(String)` |
| VehicleRepository | `findByLicensePlate(String)`, `findByCustomerId(Long)` |
| PolicyRepository | `findByPolicyNumber(String)`, `findByCustomerId(Long)` |
| ClaimRepository | `findByClaimNumber(String)`, `findByPolicyId(Long)` |
| QuoteRepository | （继承 CRUD） |
| PolicyDocumentRepository | （继承 CRUD） |

### 2.6 种子数据（SEED-001 ~ SEED-013）：PASS

- 幂等保护：`customerRepository.count() > 0` 时跳过 ✓
- 5 用户 + 5 车，覆盖三种风险等级 ✓

| userId | 姓名 | 驾龄 | 事故 | 车型 | 车价 | 预期核保 |
|--------|------|:--:|:--:|------|------:|---------|
| low-risk-user | Alice Wang | 15 | 1 | Toyota RAV4 2022 | 300k | APPROVED |
| medium-risk-user | Bob Chen | 4 | 2 | Honda Civic 2018 | 180k | REFERRED |
| high-risk-user | Charlie Zhang | 1 | 3 | BMW X5 2013 | 600k | DECLINED |
| user | John Doe | 15 | 2 | Toyota RAV4 2020 | 250k | (兼容) |
| admin | Jane Smith | 8 | 0 | Tesla Model 3 2022 | 450k | (兼容) |

---

## 3. S1.2 安全认证 — 静态验证

### 3.1 SecurityConfig Bean 装配（SEC-BEAN-001 ~ SEC-BEAN-008）：PASS

| Bean | 状态 |
|------|:--:|
| `SecurityFilterChain` | ✓ |
| `UserDetailsService` (InMemoryUserDetailsManager) | ✓ |
| `PasswordEncoder` (BCrypt) | ✓ |
| `RoleHierarchy` | ✓ |
| `MethodSecurityExpressionHandler` | ✓ |
| `@Profile("!test & !e2e")` | ✓ |
| `@EnableMethodSecurity(prePostEnabled = true)` | ✓ |

### 3.2 角色与权限矩阵（SEC-USR-001 ~ SEC-USR-010）：PASS

| 用户 | 密码 | 角色 | 核心权限 |
|------|------|------|---------|
| `user` | `password` | USER | underwriting:read, chat:use, policies:read |
| `underwriter` | `underwriter` | UNDERWRITER | + underwriting:write, underwriting:approve |
| `claims` | `claims` | CLAIMS | + claims:write, claims:read, claims:review |
| `admin` | `admin` | ADMIN | + rag:admin, policies:write, chat:admin |

密码经 BCrypt 编码存储 ✓

### 3.3 角色层级继承（ROL-001 ~ ROL-009）：PASS

```
ADMIN > UNDERWRITER > USER
ADMIN > CLAIMS      > USER
```

### 3.4 URL 访问控制（URL-001 ~ SEC-MTH-002）：PASS

- `/swagger-ui/**`、`/v3/api-docs/**`、`/api/insurance/health` → `permitAll()` ✓
- 其余 `/api/**` → `authenticated()` ✓
- CSRF 已关闭 ✓

---

## 4. S1.3 LLM 模型路由 — 静态验证

### 4.1 application.yml 模型映射（LLM-CFG-001 ~ LLM-CFG-006）：PASS

| 逻辑角色 | 物理模型 | 状态 |
|---------|---------|:--:|
| fast | deepseek-chat | ✓ |
| balanced | deepseek-chat | ✓ |
| powerful | deepseek-reasoner | ✓ |
| best | deepseek-reasoner | ✓ |
| api-key | `${DEEPSEEK_API_KEY}` | ✓ |

### 4.2 LlmSelectionService 方法映射（LLM-SVC-001 ~ LLM-SVC-019）：PASS

| 方法 | 路由角色 | 方法 | 路由角色 |
|------|---------|------|---------|
| `forSimpleQuery()` | fast | `forClaims()` | balanced |
| `forRetrieval()` | fast | `forChat()` | balanced |
| `forSummarization()` | balanced | `forEmbedding()` | embedding |
| `forComplexReasoning()` | powerful | `forAuto()` | auto |
| `forUnderwriting()` | balanced | `forModel(name)` | 指定模型 |
| `forComplexity(0-30)` | fast | `forComplexity(31-60)` | balanced |
| `forComplexity(61-100)` | powerful | | |

### 4.3 角色常量（LLM-CONST-001 ~ LLM-CONST-004）：PASS

`ROLE_FAST="fast"`, `ROLE_BALANCED="balanced"`, `ROLE_POWERFUL="powerful"`, `ROLE_EMBEDDING="embedding"` ✓

---

## 5. S1.4 配置文件 — 静态验证

### 5.1 application.yml 字段（YAML-001 ~ YAML-016）：PASS

| 属性 | 值 | 状态 |
|------|-----|:--:|
| `spring.datasource.url` | `jdbc:h2:mem:claimdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` | ✓ |
| `spring.jpa.hibernate.ddl-auto` | `update` | ✓ |
| `spring.jpa.open-in-view` | `false` | ✓ |
| `spring.h2.console.enabled` | `true` | ✓ |
| `spring.h2.console.path` | `/h2-console` | ✓ |
| `server.port` | `8080` | ✓ |
| `claim.rag.documents` | `claims_guide.md,faq.md` | ✓ |
| `claim.rag.auto-ingest` | `true` | ✓ |
| `claim.rag.lucene.chunk-size` | `1000` | ✓ |
| `claim.rag.lucene.chunk-overlap` | `200` | ✓ |
| `logging.level.com.fastclaim` | `DEBUG` | ✓ |

### 5.2 H2 表结构验证：PASS（推断）

编译通过 + `ddl-auto: update` = Hibernate 自动生成 6 张表（CUSTOMER / VEHICLE / QUOTE / POLICY / CLAIM / POLICY_DOCUMENT）。

---

## 6. 验收检查清单汇总

### 数据层（S1.1）
- [x] 6 个实体类
- [x] 6 个 Repository 接口
- [x] 3 个枚举类值集合匹配
- [x] Sentinel 方法（Customer + Vehicle）
- [x] Customer.getAge() 派生方法
- [x] DataInitializer 幂等保护
- [x] 种子数据 5 用户 + 5 车，三种风险等级

### 安全（S1.2）
- [x] SecurityConfig 仅有条件加载（!test & !e2e）
- [x] 4 个内置用户
- [x] BCrypt 密码编码
- [x] 角色层级 ADMIN > UNDERWRITER/CLAIMS > USER
- [x] permitAll / authenticated URL 规则
- [x] `@EnableMethodSecurity(prePostEnabled=true)`

### LLM 路由（S1.3）
- [x] 4 个逻辑角色映射
- [x] `DEEPSEEK_API_KEY` 占位
- [x] LlmSelectionService 全部 11 个方法
- [x] 角色常量一致性

### 配置（S1.4）
- [x] H2 内存数据源
- [x] ddl-auto=update
- [x] open-in-view=false
- [x] H2 Console 启用
- [x] RAG 配置完整

---

## 7. 缺口与建议

### 7.1 测试覆盖（严重缺口）

`src/test/java/` 目录为空，test.md 附录 A 所列 18 个测试类均未创建：

| 优先级 | 测试类 | 覆盖用例数 |
|:--:|--------|:--:|
| P0 | `entity/EnumTest.java` | 4 |
| P0 | `entity/EntityFieldMappingTest.java` | 35 |
| P0 | `entity/SentinelTest.java` | 7 |
| P0 | `entity/CustomerAgeTest.java` | 5 |
| P0 | `service/LlmSelectionServiceTest.java` | 19 |
| P1 | `repository/CustomerRepositoryTest.java` | 4 |
| P1 | `repository/VehicleRepositoryTest.java` | 4 |
| P1 | `repository/PolicyRepositoryTest.java` | 3 |
| P1 | `repository/ClaimRepositoryTest.java` | 3 |
| P1 | `repository/QuoteRepositoryTest.java` | 1 |
| P1 | `repository/PolicyDocumentRepositoryTest.java` | 1 |
| P1 | `config/SecurityConfigTest.java` | 8 |
| P1 | `config/UserDetailsServiceTest.java` | 10 |
| P1 | `config/RoleHierarchyTest.java` | 9 |
| P1 | `config/UrlAccessControlTest.java` | 12 |
| P1 | `config/EmbabelModelConfigTest.java` | 6 |
| P1 | `config/ApplicationYamlTest.java` | 16 |
| P2 | `config/DataInitializerIntegrationTest.java` | 13 |

### 7.2 环境变量

`DEEPSEEK_API_KEY` 测试配置已设占位值（`sk-test-placeholder-for-integration-tests`），L4 端到端需要真实 key。

### 7.3 已知风险

| 风险 | 等级 | 当前处理 |
|------|:--:|---------|
| H2 内存库重启清空 | 低 | MVP 阶段接受 |
| InMemoryUserDetailsManager | 中 | 开发/演示用，生产需替换 |
| 无测试覆盖 | **严重** | 需补齐 |
| Sentinel 持久化风险 | 低 | 业务层待过滤（阶段二） |
