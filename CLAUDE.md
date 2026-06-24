# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指引。

## 约束（必须遵守）

1. **代码生成强制遵循《阿里巴巴Java开发手册 泰山版》全部强制规则**，详见 `.claude/rules/alibaba-lint.md`
2. 生成代码时须在关键逻辑处添加必要的中文注释，注释说明 WHY 而非 WHAT
3. 生成代码时须使用 LoggerFactory 添加必要日志：
   - Service/Component/Config 类：使用 `private static final Logger log = LoggerFactory.getLogger(Xxx.class)` 声明日志实例
   - 关键操作（初始化、状态变更、错误恢复）使用 `log.info()` 或 `log.warn()`
   - 调试信息（单号生成、模型路由选择、参数值）使用 `log.debug()`
   - 日志内容使用中文，占位符使用 `{}`
   - Entity 类仅在构造函数（单号生成）和 Sentinel 工厂方法（`lookupFailed()`）处添加日志
   - Repository 接口不需要日志
4. 修改 CLAUDE.md 时须用中文沟通确认
5. 以上四条为强制性约束，不可跳过或忽略

## 构建与运行

```bash
# 前置条件
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# 编辑 .env 填入真实 DeepSeek API Key，启动前执行：
source .env

# 编译
./mvnw compile

# 运行（启动后访问 :8080，Swagger 在 /swagger-ui/index.html，H2 控制台在 /h2-console）
./mvnw spring-boot:run

# 测试
./mvnw test                              # 全部测试
./mvnw test -Dtest=XxxAgentTest          # 单个测试类

# 打包
./mvnw package -DskipTests
```

## 架构

**技术栈**: Spring Boot 3.4.0 + Java 21 + Embabel Agent 0.3.5（面向 Spring Boot 的 AI Agent 框架）。

**核心模式**: 通过 `@Agent` 注解声明智能体，`@Action` 注解标记流水线步骤。Embabel 框架通过 `@State` 密封接口或 `PlannerType.UTILITY` 自动推导执行路径。每个 Agent 实现 `StuckHandler` 以处理超时/错误恢复。

**已有代码分层**:
- `entity/` — 6 个 JPA 实体: Customer(客户)、Vehicle(车辆)、Quote(报价单)、Policy(保单)、Claim(理赔单)、PolicyDocument(知识库文档)
- `repository/` — Spring Data JPA 接口
- `config/` — `SecurityConfig`（HTTP Basic 认证，`@Profile("!test & !e2e")`）和 `DataInitializer`（幂等种子数据）
- `service/` — `LlmSelectionService` 将逻辑角色（fast/balanced/powerful）映射到 DeepSeek 模型

**待建分层**（参见 docs/mvp* 规格文档）:
- `agent/` — @Agent 类（ClaimsAgent、UnderwritingAgent、ChatbotAgent）
- `controller/` — REST 端点
- `guardrail/` — 通过 Embabel 的 `UserInputGuardRail`/`AssistantMessageGuardRail` 实现输入/输出护栏
- `dto/` — 请求/响应 DTO

**LLM 路由**: 逻辑角色（fast→deepseek-chat、balanced→deepseek-chat、powerful→deepseek-reasoner）在 `application.yml` 中映射。Agent 通过 `LlmSelectionService` 调用 `LlmOptions.withLlmForRole(role)` 选择模型。

**数据库**: H2 内存模式（`jdbc:h2:mem:claimdb`），开发环境 `ddl-auto: update`，测试环境 `create-drop`。测试库使用 `jdbc:h2:mem:testdb`。生产环境可替换为 PostgreSQL。

**RAG**: Embabel Lucene RAG（BM25 纯文本检索，无向量）+ Apache Tika 文档解析。测试 profile 设置 `claim.rag.auto-ingest: false`。

**认证**: 4 种角色（USER/UNDERWRITER/CLAIMS/ADMIN），层级继承（`ADMIN > UNDERWRITER > USER`，`ADMIN > CLAIMS > USER`）。test/e2e profile 下禁用安全配置。

## 关键模式

- **Sentinel 占位对象**: `Customer.lookupFailed()` 和 `Vehicle.lookupFailed()` 返回 `__sentinel__` ID 的占位对象。当 LLM 查找实体失败时，UTILITY 规划器需要类型化的槽位填入 Blackboard 以避免 STUCK 状态。
- **错误传播**: 错误写入 Blackboard（`context.bind("error_key", msg)`），而非抛出异常。框架会对异常进行重试。
- **Agentic RAG**: `ToolishRag` 将 Lucene 搜索包装为 LLM 可调用的工具 — LLM 自主决定何时检索以及检索什么内容。

## 项目阶段（docs/）

- `docs/业务分析.md` — 业务分析文档
- `docs/mvp1/spec.md` + `plan.md` + `task.md` — 阶段一：数据层、安全认证、LLM 路由（当前阶段）
- `docs/mvp2/spec2.md` — 阶段二：核保 Agent
- `docs/mvp3/spec3.md` — 阶段三：理赔 Agent
- `docs/mvp4/spec4.md` — 阶段四：客服 Chatbot + 全流程集成
- `docs/er-diagram.md` + `docs/er-diagram.html` — ER 图
