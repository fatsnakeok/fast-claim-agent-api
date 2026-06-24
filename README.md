# fast-claim-agent-api

智能保险 AI 客服平台，基于 Spring Boot 3.4 + Embabel Agent 框架 + DeepSeek 大模型，提供保险知识问答、理赔咨询等智能化服务。

## 技术栈

- **语言**: Java 21
- **框架**: Spring Boot 3.4.0 + Spring Security + Spring Data JPA
- **AI 框架**: Embabel Agent 0.3.5（Agent 编排、护栏、RAG）
- **大模型**: DeepSeek Chat / DeepSeek Reasoner
- **检索引擎**: Apache Lucene BM25（全文检索，非向量）
- **文档解析**: Apache Tika
- **数据库**: H2（内存模式，可替换为 PostgreSQL）
- **API 文档**: SpringDoc OpenAPI + Swagger UI

## 功能特性

### 对话服务

- **同步对话** (`POST /api/chat`) — 基于 Embabel Agent 框架的 Agentic RAG 模式，LLM 自主调用知识库工具检索后回答
- **流式对话** (`POST /api/chat/stream`) — SSE 协议逐 token 推送，直接注入 RAG 检索结果到 prompt，实时打字机效果
- **会话管理** — 服务端维护对话历史（最多 20 轮），支持 30 分钟 TTL 自动过期清理
- **多轮对话** — 携带历史上下文，支持追问和延续话题

### 安全护栏

- **输入护栏** — 拦截注入攻击（SQL 注入、Base64 编码注入、越狱指令）和无关话题
- **输出护栏** — 检测敏感信息泄露（身份证、银行卡、手机号）、幻觉信号、异常响应长度，告警不阻断

### 知识库 RAG

- 三份保险知识库文档（车险条款、理赔指南、常见问题），启动时自动入库
- Lucene BM25 纯文本检索，chunk-size 1000 / chunk-overlap 200
- 支持全文删除重建、增量入库、单文档入库

### 认证与权限

- HTTP Basic 认证，4 种角色层级继承：`ADMIN > UNDERWRITER > USER`，`ADMIN > CLAIMS > USER`
- 端点权限控制（`chat:use` 权限）
- 测试环境可禁用安全配置

### 数据层

- 6 个 JPA 实体：Customer（客户）、Vehicle（车辆）、Quote（报价单）、Policy（保单）、Claim（理赔单）、PolicyDocument（知识库文档）
- 启动时自动插入幂等种子数据（5 个客户及车辆）
- H2 控制台（`/h2-console`）

### 浏览器测试页

- 内嵌聊天测试页面（`/chat-stream-test.html`），无须额外前端
- 支持流式 SSE 接收、Markdown 渲染、会话信息展示
- Markdown 渲染支持：标题、列表、粗体、引用块、代码块、中文序号段落

## 快速开始

### 前置条件

- Java 21
- DeepSeek API Key

### 运行

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
source .env    # .env 中配置 DEEPSEEK_API_KEY=sk-xxx

./mvnw compile
./mvnw spring-boot:run
```

启动后访问：
- 应用: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 控制台: `http://localhost:8080/h2-console`（JDBC URL: `jdbc:h2:mem:claimdb`，用户名 `sa`，空密码）
- 聊天测试页: `http://localhost:8080/chat-stream-test.html`

### 测试

```bash
./mvnw test                              # 全部测试
./mvnw test -Dtest=XxxAgentTest          # 单个测试类
```

### 打包

```bash
./mvnw package -DskipTests
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/chat` | 同步对话，返回完整回答 |
| `POST` | `/api/chat/stream` | 流式对话，SSE 协议逐 token 推送 |
| `DELETE` | `/api/chat/sessions/{sessionId}` | 清除指定会话 |

认证方式：HTTP Basic（`user:password`）。

### 错误响应

| HTTP 状态码 | 错误类型 | 说明 |
|-------------|----------|------|
| 422 | `INPUT_REJECTED` | 输入被护栏拦截 |
| 404 | `SESSION_NOT_FOUND` | 会话不存在 |
| 410 | `SESSION_EXPIRED` | 会话已过期 |

## 项目结构

```
src/main/java/com/fastclaim/
├── FastClaimAgentApiApplication.java  # 启动类
├── agent/
│   └── ChatbotAgent.java              # 保险 AI 客服 Agent
├── config/
│   ├── CacheConfiguration.java        # 缓存配置
│   ├── DataInitializer.java           # 种子数据
│   ├── DocumentIngestionRunner.java   # 文档入库启动器
│   ├── OpenApiConfiguration.java      # Swagger 配置
│   ├── RagConfiguration.java          # RAG 配置
│   └── SecurityConfig.java            # 安全配置
├── controller/
│   ├── ChatController.java            # 对话 API 端点
│   └── GlobalExceptionHandler.java    # 全局异常处理
├── dto/
│   ├── ChatMessage.java               # 对话消息记录
│   ├── ChatOutput.java                # Agent 输出
│   ├── ChatRequest.java               # 请求体
│   ├── ChatResponse.java              # 响应体
│   ├── ChatSession.java               # 会话管理
│   └── UserInput.java                 # Agent 输入
├── entity/
│   ├── Claim.java                     # 理赔单
│   ├── Customer.java                  # 客户
│   ├── Policy.java                    # 保单
│   ├── PolicyDocument.java            # 知识库文档
│   ├── Quote.java                     # 报价单
│   ├── Vehicle.java                   # 车辆
│   └── enums/                         # 状态枚举
├── guardrail/
│   ├── InsuranceUserInputGuardRailImpl.java
│   └── InsuranceAssistantMessageGuardRailImpl.java
├── repository/                        # Spring Data JPA 接口
└── service/
    ├── CacheService.java              # 二级缓存
    ├── ChatService.java               # 对话核心编排
    ├── DocumentIngestionService.java  # 文档入库
    ├── InsuranceKnowledgeBase.java    # 知识库检索
    └── LlmSelectionService.java       # LLM 路由选择
```

## 配置要点

```yaml
chat:
  session:
    ttl-minutes: 30          # 会话过期时间
    max-history-rounds: 20   # 最大历史轮数（含 tool 调用轮次）

deepseek:
  stream:
    base-url: https://api.deepseek.com
    model: deepseek-chat      # 流式模型
```

API Key 通过环境变量 `DEEPSEEK_API_KEY` 注入，写入 `.env` 文件后 `source .env` 加载。
