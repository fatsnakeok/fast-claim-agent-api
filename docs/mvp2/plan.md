# 阶段二概要设计：ChatbotAgent — AI 客服 MVP

> 对应 `spec.md`，覆盖 Agentic RAG 智能体、Lucene 检索引擎、会话管理、护栏体系、REST API、缓存。

---

## 1. 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                      Spring Boot 3.4.0                            │
│                                                                   │
│   ┌──────────────┐   ┌──────────────┐   ┌───────────────────┐    │
│   │ ChatController│   │  ChatService │   │   ChatbotAgent     │    │
│   │ (REST API)   │──▶│ (会话+编排)   │──▶│ (Embabel @Agent)   │    │
│   └──────────────┘   └──────┬───────┘   └────────┬──────────┘    │
│                              │                     │               │
│                    ┌─────────┼───────┐    ┌───────┴──────────┐    │
│                    ▼         ▼       │    │  ToolishRag       │    │
│              ┌─────────┐ ┌───────┐  │    │  (LLM 可调用工具)  │    │
│              │ GuardRail│ │Cache  │  │    └───────┬──────────┘    │
│              │ 输入/输出 │ │Service│  │            │               │
│              └─────────┘ └───────┘  │    ┌───────┴──────────┐    │
│                                      │    │ DocumentIngestion │    │
│                                      │    │ Service + Runner  │    │
│                                      │    └───────┬──────────┘    │
│                                      │            │               │
│                                      ▼            ▼               │
│                              ┌──────────────────────────┐        │
│                              │   Lucene 索引 (内存)       │        │
│                              │   + ConcurrentHashMap     │        │
│                              │   会话存储                  │        │
│                              └──────────────────────────┘        │
│                                                                   │
│   ── 已有（阶段一） ──────────────────────────────────────        │
│   LlmSelectionService  │  SecurityConfig  │  JPA Entities         │
└──────────────────────────────────────────────────────────────────┘
```

**新增分层职责**：
- **ChatController** — POST /api/chat、DELETE /api/chat/sessions/{id}，参数校验
- **ChatService** — 会话生命周期管理，护栏调度，Agent 调用编排，对话历史裁剪
- **ChatbotAgent** — Embabel UTILITY 规划器智能体，单 @Action 执行 Agentic RAG
- **ToolishRag** — 将 Lucene BM25 搜索包装为 LLM 工具，LLM 自主决定检索策略
- **GuardRail** — 用户输入护栏（注入/无关话题检测）+ LLM 回复护栏（敏感信息/幻觉检测）
- **CacheService** — Spring Cache + 本地 TTL 缓存，覆盖 LLM 响应和 RAG 搜索结果
- **DocumentIngestionService + Runner** — 启动时自动将 Markdown 知识库文档分块摄入 Lucene 索引

**与阶段一的关系**：本阶段新增 ChatbotAgent 和配套的 RAG/会话/护栏基础设施，复用阶段一的 `LlmSelectionService`（`forChat()` → balanced）和安全认证体系。

---

## 2. ChatbotAgent 设计

### 2.1 智能体定义

```java
@Agent(description = "保险 AI 客服", planner = PlannerType.UTILITY)
public class ChatbotAgent implements StuckHandler {
    // 单 @Action，同时是 @AchievesGoal
}
```

- **规划器**：`UTILITY` — 框架按返回类型推导执行路径，无需显式 @State 密封接口
- **单动作模式**：只有一个 `answerQuestion` @Action，输入 `UserInput`，输出 `ChatOutput`
- **StuckHandler**：超时时打印 Blackboard 诊断信息，返回 `NO_RESOLUTION`

### 2.2 answerQuestion @Action 工作流

```
UserInput（用户消息 + 对话历史上下文）
  │
  ├─ context.ai()
  │    .withReference(insuranceRag)     ← 注册 RAG 为 LLM 工具
  │    .withLlmOptions(llmService.forChat())  ← 使用 balanced 模型
  │
  ├─ LLM 自主调用 insurance_docs_textSearch     ← 检索知识库
  │    ├─ 根据用户问题构造查询词
  │    ├─ 获取 Lucene BM25 检索结果（chunks）
  │    └─ 可多轮迭代检索后再回答
  │
  └─ 综合检索结果 + 对话历史 → 生成 ChatOutput(answer)
```

**设计要点**：采用 Agentic RAG 而非传统"先检索→注入→回答"模式。LLM 自主决定何时检索、用什么查询词检索，可多次迭代检索不同角度后再回答，比传统 pipeline 更灵活。

### 2.3 DTO 设计

| 类 | 类型 | 字段 | 说明 |
|----|------|------|------|
| `UserInput` | record | `String message` | @Action 输入 |
| `ChatOutput` | record | `String answer` | @Action 输出 |
| `ChatRequest` | record | `String message` | POST /api/chat 请求体 |
| `ChatResponse` | record | `String response, String sessionId, boolean isNewSession, LocalDateTime timestamp` | POST /api/chat 响应 |

---

## 3. RAG 检索架构

### 3.1 架构选型

纯文本 BM25 检索，不依赖向量嵌入，适合快速启动和低资源场景。

### 3.2 RagConfiguration

```java
@Configuration
public class RagConfiguration {

    @Bean
    public LuceneSearchOperations luceneSearchOperations() {
        // indexName = "insurance-lucene"
        // chunkSize = 1000, chunkOverlap = 200
        // embeddingService = null, vectorWeight = 0.0  ← 纯 BM25
    }

    @Bean
    public ToolishRag insuranceRag(LuceneSearchOperations ops) {
        // name = "insurance_docs"
        // description = "Search insurance-related documents..."
        // wrappedSearchOperations = ops
    }
}
```

**关键参数**：
| 参数 | 值 | 说明 |
|------|-----|------|
| indexName | insurance-lucene | Lucene 索引名称 |
| chunkSize | 1000 | 每块字符数 |
| chunkOverlap | 200 | 块间重叠字符数 |
| embeddingService | null | 无向量嵌入 |
| vectorWeight | 0.0 | 100% BM25 权重 |

### 3.3 知识库文档

| 文件 | 路径 | 内容 |
|------|------|------|
| `comprehensive_vehicle_insurance.md` | `classpath:documents/` | 机动车辆综合保险条款 |
| `claims_guide.md` | `classpath:documents/` | 机动车辆保险理赔服务指南 |
| `faq.md` | `classpath:documents/` | 常见问题解答 |

### 3.4 DocumentIngestionService

- 解析 Markdown → `MaterializedDocument` 层级模型（标题→段落→句子）
- 按 chunk_size=1000, overlap=200 分块
- 支持三种摄入模式：
  - **全量摄入**（`ingestAll()`）：启动时执行
  - **增量摄入**（`ingestDelta()`）：仅摄入新增/变更文档
  - **单文档摄入**（`ingestDocument(name)`）：手动触发

### 3.5 DocumentIngestionRunner

实现 `CommandLineRunner`，应用启动时：
1. 检查 `claim.rag.auto-ingest` 配置（默认 true）
2. 从 `classpath:documents/` 加载 Markdown 文件
3. 调用 `DocumentIngestionService.ingestAll()` 建立索引
4. 非生产环境打印摄入统计日志

---

## 4. ChatService 会话管理

### 4.1 会话存储

| 属性 | 值 |
|------|-----|
| 存储结构 | `ConcurrentHashMap<String, ChatSession>` |
| 会话 TTL | 30 分钟（最后一次交互起算） |
| 历史上限 | 20 轮对话（一轮 = user + assistant 各一条） |
| 会话 ID | `UUID.randomUUID()` 生成 |
| 过期清理 | `@Scheduled(fixedRate=300000)` 每 5 分钟扫描清理 |

### 4.2 ChatSession 数据结构

```java
public class ChatSession {
    private String sessionId;
    private String userId;
    private List<ChatMessage> messages;    // 最多 40 条（20 轮）
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private boolean isActive;
}

public record ChatMessage(String role, String content, LocalDateTime timestamp) {}
```

### 4.3 processChat 流程

```
POST /api/chat
  │
  ├─ 1. 获取/创建会话
  │      ├─ sessionId 为空 → 新建 ChatSession，返回 isNewSession=true
  │      └─ sessionId 非空 → 从 Map 查找，校验 TTL，恢复对话历史
  │
  ├─ 2. 用户输入护栏校验
  │      ├─ 注入检测（prompt injection、SQL injection）
  │      ├─ 无关话题检测（stock、weather、politics 等）
  │      ├─ 不通过 → 返回 422 + 拒绝原因
  │      └─ 通过 → 继续
  │
  ├─ 3. 构造对话历史上下文
  │      └─ 取最近 20 轮对话，格式化为 UserInput 上下文
  │
  ├─ 4. AgentInvocation.invoke(ChatbotAgent, userInput)
  │      └─ Agent 内部执行 Agentic RAG，返回 ChatOutput
  │
  ├─ 5. LLM 回复护栏校验
  │      ├─ 敏感信息检测（信用卡号、SSN 等正则）
  │      ├─ 幻觉迹象检测（不确定表述）
  │      ├─ 长度异常检测（<10 或 >5000 字符）
  │      └─ 不通过 → 标记警告但不阻断回复
  │
  ├─ 6. 保存对话记录（user message + assistant response），更新 lastAccessedAt
  │
  └─ 7. 返回 ChatResponse(sessionId, answer, isNewSession, timestamp)
```

### 4.4 会话边界场景

| 场景 | 行为 |
|------|------|
| 首次对话（无 sessionId） | 新建会话，isNewSession=true |
| 继续对话（有效 sessionId） | 恢复历史，isNewSession=false |
| 会话过期（>30min 未活动） | 返回 410 GONE，提示新建会话 |
| 会话不存在（无效 ID） | 返回 404 |
| 超过 20 轮 | 自动截断最早的消息，保留最近 20 轮 |
| DELETE /api/chat/sessions/{id} | 从 Map 移除，返回 204 |

---

## 5. 护栏体系

### 5.1 用户输入护栏 — InsuranceUserInputGuardRailImpl

实现 Embabel 的 `UserInputGuardRail` 接口。

| 检测类型 | 规则 | 命中返回 |
|---------|------|---------|
| 注入指令 | `ignore all rules`, `auto approve`, `bypass review`, `system prompt`, `admin mode` 等 | 422 |
| 提示注入 | Base64 编码指令（正则 `[A-Za-z0-9+/]{20,}={0,2}` 且可解码为注入模式） | 422 |
| SQL 注入 | `DROP TABLE`, `INSERT INTO`, `DELETE FROM`, `--`, `';` 等 | 422 |
| 无关话题 | stock, weather, sports, politics, gambling, cryptocurrency | 422 |

**拒绝响应体**：
```json
{
  "error": "INPUT_REJECTED",
  "message": "您的消息包含不相关内容，请重新输入",
  "timestamp": "2026-06-23T14:30:00"
}
```

### 5.2 LLM 回复护栏 — InsuranceAssistantMessageGuardRailImpl

实现 Embabel 的 `AssistantMessageGuardRail` 接口。

| 检测类型 | 规则 | 动作 |
|---------|------|------|
| 信用卡号 | `\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}` | 标记警告 |
| 身份证号 | `\d{17}[\dXx]` | 标记警告 |
| 手机号/密码/密钥 | 中文手机号、password/key 上下文 | 标记警告 |
| 幻觉迹象 | "I'm not sure", "might be", "could be wrong", "I think" | 标记警告 |
| 长度异常 | 回答 < 10 字符或 > 5000 字符 | 标记警告 |

**核心理念**：输入护栏阻断，输出护栏仅标记警告不阻断 — 因为阻断 LLM 回复会导致用户无响应。

---

## 6. REST API 设计

### 6.1 ChatController — `/api/chat`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/chat` | `hasAuthority('chat:use')` | 发送消息 |
| DELETE | `/api/chat/sessions/{sessionId}` | `hasAuthority('chat:use')` | 清除会话 |

### 6.2 POST /api/chat

**请求**：
```json
{
  "message": "我的车被追尾了，保险赔不赔？"
}
```
**查询参数**：`?sessionId=xxx`（可选，首次不传）

**响应（200）**：
```json
{
  "response": "根据机动车综合保险条款，追尾事故属于碰撞责任范围...",
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "isNewSession": false,
  "timestamp": "2026-06-23T14:30:00"
}
```

**错误码**：
| 状态码 | 场景 |
|--------|------|
| 400 | message 为空或仅空白字符 |
| 401 | 未认证 |
| 403 | 无 chat:use 权限 |
| 404 | sessionId 对应的会话不存在 |
| 410 | 会话已过期（>30min） |
| 422 | 输入护栏拒绝 |

### 6.3 DELETE /api/chat/sessions/{sessionId}

**响应（204）**：无响应体
**错误**：404（会话不存在）

---

## 7. 缓存设计

### 7.1 二级缓存架构

| 层级 | 实现 | 用途 | TTL |
|------|------|------|-----|
| L1 | Spring Cache (ConcurrentMapCacheManager) | `llm-responses`：按 prompt 内容哈希缓存 LLM 回复 · `rag-searches`：按查询词哈希缓存 RAG 搜索结果 | 无 TTL（随 L2 清理） |
| L2 | `ConcurrentHashMap<String, CachedEntry>` + 定时清理 | 管理 TTL 过期，`@Scheduled(fixedRate=300000)` 每 5 分钟清理过期条目 | 5 分钟 |

### 7.2 缓存 Key 策略

| 缓存域 | Key 生成规则 | 说明 |
|--------|------------|------|
| llm-responses | `MD5(prompt + conversationContext)` | 相同 prompt + 相同上下文 → 命中缓存 |
| rag-searches | `MD5(query)` | 相同搜索词 → 命中缓存 |

### 7.3 CacheConfiguration

```java
@Configuration
@EnableCaching
public class CacheConfiguration {
    // 注册 ConcurrentMapCacheManager
    // 声明 "llm-responses" 和 "rag-searches" 两个缓存
}
```

---

## 8. 配置更新

在 `application.yml` 中新增/调整以下段：

```yaml
insurance:
  rag:
    documents-path: classpath:documents/
    documents: comprehensive_vehicle_insurance.md,claims_guide.md,faq.md
    auto-ingest: true
    lucene:
      name: insurance-lucene
      chunk-size: 1000
      chunk-overlap: 200

chat:
  session:
    ttl-minutes: 30
    max-history-rounds: 20
    cleanup-interval-ms: 300000
  cache:
    ttl-minutes: 5
```

> 注意：spec 中配置前缀为 `insurance.rag`，但当前 `application.yml` 实际使用 `claim.rag`。需统一为 `insurance.rag`，同时更新阶段一中引用了 `claim.rag` 的代码。

---

## 9. 依赖变更

### 9.1 新增 Maven 依赖

```xml
<!-- Embabel Agent RAG Lucene -->
<dependency>
    <groupId>com.embabel</groupId>
    <artifactId>embabel-agent-rag-lucene</artifactId>
</dependency>

<!-- Embabel Agent RAG Tika（文档解析） -->
<dependency>
    <groupId>com.embabel</groupId>
    <artifactId>embabel-agent-rag-tika</artifactId>
</dependency>
```

---

## 10. 实施清单

| 序号 | 任务 | 产出 | 依赖 |
|------|------|------|------|
| 1 | 创建 DTO 类（UserInput, ChatOutput, ChatRequest, ChatResponse, ChatSession, ChatMessage） | `dto/` | — |
| 2 | 配置 RAG（RagConfiguration + LuceneSearchOperations + ToolishRag） | `config/RagConfiguration.java` | 阶段一 LlmSelectionService |
| 3 | 实现文档摄入服务（DocumentIngestionService） | `service/DocumentIngestionService.java` | T2 |
| 4 | 实现启动摄入 Runner（DocumentIngestionRunner） | `config/DocumentIngestionRunner.java` | T3 |
| 5 | 实现 ChatbotAgent（单 @Action Agentic RAG） | `agent/ChatbotAgent.java` | T2 |
| 6 | 实现用户输入护栏（InsuranceUserInputGuardRailImpl） | `guardrail/InsuranceUserInputGuardRailImpl.java` | — |
| 7 | 实现 LLM 回复护栏（InsuranceAssistantMessageGuardRailImpl） | `guardrail/InsuranceAssistantMessageGuardRailImpl.java` | — |
| 8 | 实现 ChatService（会话管理 + 编排） | `service/ChatService.java` | T1, T5, T6, T7 |
| 9 | 实现 ChatController（REST API） | `controller/ChatController.java` | T8 |
| 10 | 实现 CacheService + CacheConfiguration | `service/CacheService.java` · `config/CacheConfiguration.java` | — |
| 11 | 更新 application.yml（insurance.rag + chat.* 段） | `resources/application.yml` | — |

**建议实施顺序**：T1 → (T2, T3, T4 串行) → T5 → (T6, T7 并行) → T8 → T9 → (T10, T11 并行)

---

## 11. 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| Agentic RAG | LLM 自主决定检索策略 | 比传统"先检索→注入→回答"更灵活，LLM 可多轮迭代检索 |
| 纯 BM25 检索 | 无向量嵌入 | 快速启动，低资源场景；文档量小（3篇），语义检索优势不明显 |
| 会话存储 | ConcurrentHashMap（内存） | MVP 阶段简单可靠，无外部依赖；后续可迁移到 Redis |
| 会话 TTL | 30 分钟 | 覆盖典型客服对话时长，避免内存无限增长 |
| 历史截断 | 20 轮 | 平衡 LLM 上下文窗口与内存占用的折中值 |
| 输入护栏阻断 | 422 直接拒绝 | 安全优先，恶意输入不应到达 LLM |
| 输出护栏不阻断 | 仅标记警告 | LLM 回复被阻断会导致用户无响应，用户体验不可接受 |
| 缓存 TTL | 5 分钟 | 覆盖短时间内的重复查询，过期清理控制内存 |
| 配置前缀 | `insurance.rag` 替代 `claim.rag` | 统一命名空间，与知识库文档的业务语义一致 |
