# 阶段二需求规格：ChatbotAgent — AI 客服 MVP

> 以 ChatbotAgent 为核心交付第一个可用的智能保险客服助手。
> 前置依赖：阶段一（数据层与基础设施，见 `spec1.md`）。


---

## S2.1 MVP 概述

### S2.1.1 本阶段目标

交付一个**基于 Agentic RAG 的 AI 保险客服**，用户可通过 REST API 发送自然语言问题，系统从保险知识库文档中检索相关信息并生成回答。支持多轮对话，会话由服务端管理。

### S2.1.2 交付物es

| 模块 | 内容 |
|------|------|
| **ChatbotAgent** | 单 @Action 的 Agentic RAG 智能体，LLM 自主检索知识库并回答 |
| **RAG 检索引擎** | Lucene BM25 全文检索，启动时自动摄入 Markdown 知识库文档 |
| **会话管理** | 服务端会话生命周期（TTL 30min），对话历史截断（最多 20 轮） |
| **护栏体系** | 用户输入护栏（注入检测、无关话题过滤）+ LLM 回复护栏（敏感信息、幻觉检测） |
| **客服 API** | POST /api/chat 发送消息，DELETE /api/chat/sessions/{id} 清除会话 |
| **缓存** | Spring Cache + 本地 TTL 缓存，缓存 LLM 响应和 RAG 搜索结果 |

### S2.1.3 用户故事

| 编号 | 故事 | 验收标准 |
|------|------|---------|
| US-C1 | 作为普通用户，我想咨询车险相关问题，获得基于真实条款的回答 | 发送自然语言问题后，返回引用知识库文档的 AI 回答 |
| US-C2 | 作为普通用户，我想进行多轮对话，系统能记住上下文 | 同一 sessionId 下，后续问题能参考前文 |
| US-C3 | 作为系统，我需要拦截恶意注入和无关话题 | 输入包含 "ignore all rules" 等注入指令时返回 422 |

---

## S2.2 ChatbotAgent 设计

### S2.2.1 智能体定义

```
@Agent(description = "保险 AI 客服", planner = PlannerType.UTILITY)
ChatbotAgent implements StuckHandler
```

- **规划器类型**：`UTILITY` — 强制框架按返回类型推导执行路径
- **只有一个 @Action**，同时也是 `@AchievesGoal`

### S2.2.2 @Action 工作流

```
用户消息 (UserInput)
  │
  └─ [1] answerQuestion(UserInput, OperationContext) → ChatOutput
         ├─ context.ai().withReference(insuranceRag)   ← 注册 RAG 工具
         ├─ LLM 自主调用 insurance_docs_textSearch     ← 检索知识库
         ├─ LLM 综合检索结果生成回答
         └─ 返回 ChatOutput(answer)
```

**关键设计**：采用 Agentic RAG 模式而非传统检索注入。LLM 自主决定何时检索、以什么查询词检索，可多轮迭代检索后再回答。这比"先检索→注入上下文→回答"的传统模式更灵活。

### S2.2.3 ChatOutput

```java
public record ChatOutput(String answer) {}
```

### S2.2.4 StuckHandler

超时时打印 Blackboard 诊断信息，返回 NO_RESOLUTION。

---

## S2.3 RAG 检索架构

### S2.3.1 概述

纯文本 BM25 检索，不依赖向量嵌入，适合快速启动和低资源场景。

### S2.3.2 RagConfiguration

```
创建 LuceneSearchOperations:
  indexName = "insurance-lucene"
  chunkSize = 1000
  chunkOverlap = 200
  embeddingService = null       ← 纯文本，无向量
  vectorWeight = 0.0            ← 100% BM25

创建 ToolishRag:
  name = "insurance_docs"
  description = "Search insurance-related documents..."
  wrappedSearchOperations = luceneSearchOperations
```

### S2.3.3 知识库文档

| 文档 | 内容 |
|------|------|
| `comprehensive_vehicle_insurance.md` | 机动车辆综合保险条款 |
| `claims_guide.md` | 机动车辆保险理赔服务指南 |
| `faq.md` | 常见问题解答 |

文档位于 `classpath:documents/`，通过 `DocumentIngestionRunner` 在应用启动时自动摄入到 Lucene 索引。

### S2.3.4 DocumentIngestionService

- 解析 Markdown → MaterializedDocument 层级模型
- 按 chunk_size=1000, overlap=200 分块
- 支持全部摄入、增量摄入、单文档摄入

### S2.3.5 配置项

```yaml
insurance:
  rag:
    documents-path: classpath:documents/
    documents: comprehensive_vehicle_insurance.md,claims_guide.md,faq.md
    auto-ingest: true                     # 启动时自动摄入
    lucene:
      name: insurance-lucene
      chunk-size: 1000
      chunk-overlap: 200
```

---

## S2.4 ChatService 会话管理

### S2.4.1 会话生命周期

| 属性 | 值 |
|------|-----|
| 存储 | ConcurrentHashMap<String, ChatSession> |
| 会话 TTL | 30 分钟（最后一次交互后） |
| 历史上限 | 20 轮对话（user + assistant 各一条算一轮） |
| 会话 ID | UUID 随机生成 |

### S2.4.2 processChat 流程

```
1. 获取/创建会话（按 userId + sessionId）
2. 用户输入护栏校验 → 不通过返回错误
3. 构造对话历史上下文
4. AgentInvocation.invoke(ChatbotAgent, userInput)
5. LLM 回复护栏校验 → 不通过标记警告
6. 保存对话记录，更新 TTL
7. 返回 ChatResponse(sessionId, response, isNewSession)
```

### S2.4.3 ChatSession 数据结构

```
ChatSession:
  - sessionId: String
  - userId: String
  - messages: List<ChatMessage>    (最多 40 条 = 20 轮)
  - createdAt: LocalDateTime
  - lastAccessedAt: LocalDateTime
  - isActive: boolean
```

---

## S2.5 护栏体系

### S2.5.1 用户输入护栏

`InsuranceUserInputGuardRailImpl` 实现 `UserInputGuardRail` 接口。

| 检测类型 | 规则示例 |
|---------|---------|
| 注入指令 | `ignore all rules`, `auto approve`, `bypass review`, `system prompt`, `admin mode` |
| 提示注入 | Base64 编码指令 |
| SQL 注入 | `DROP TABLE`, `INSERT INTO`, `DELETE FROM` 等 |
| 无关话题 | stock, weather, sports, politics, gambling, cryptocurrency |

校验不通过时 ChatService 返回 422。

### S2.5.2 LLM 回复护栏

`InsuranceAssistantMessageGuardRailImpl` 实现 `AssistantMessageGuardRail` 接口。

| 检测类型 | 规则示例 |
|---------|---------|
| 敏感信息 | 信用卡号 (\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4})、SSN、密码、密钥 |
| 幻觉迹象 | 不确定表述 ("I'm not sure", "might be", "could be wrong") |
| 长度异常 | < 10 字符 或 > 5000 字符 |

校验不通过时标记警告但不阻断回复。

---

## S2.6 REST API

### S2.6.1 ChatController — `/api/chat`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/` | `chat:use` | 发送消息。首次调用不传 sessionId，服务端创建新会话并返回 sessionId |
| DELETE | `/sessions/{sessionId}` | `chat:use` | 清除指定会话 |

### S2.6.2 POST /api/chat

**请求**：
```json
{
  "message": "我的车被追尾了，保险赔不赔？"
}
```

**请求参数**：`?sessionId=xxx`（可选，首次不传）

**响应**：
```json
{
  "response": "根据机动车综合保险条款，追尾事故属于碰撞责任范围...",
  "sessionId": "a1b2c3d4-...",
  "isNewSession": false,
  "timestamp": "2026-06-23T14:30:00"
}
```

---

## S2.7 缓存

### S2.7.1 二级缓存

| 层级 | 实现 | 用途 |
|------|------|------|
| 一级 | Spring Cache (ConcurrentMapCacheManager) | `llm-responses` — 按 prompt.hashCode() 缓存 · `rag-searches` — 按 query.hashCode() 缓存 |
| 二级 | ConcurrentHashMap<String, CachedEntry> | TTL 5 分钟，`@Scheduled(fixedRate=300000)` 定时清理 |

---

## S2.8 需求追溯矩阵

| 需求编号 | 需求项 | 对应源码 |
|---------|-------|---------|
| S2.2.1 | ChatbotAgent 定义 | `agent/ChatbotAgent.java` |
| S2.2.2 | answerQuestion @Action | `agent/ChatbotAgent.java` — `answerQuestion()` |
| S2.3.2 | RAG 配置 | `config/RagConfiguration.java` |
| S2.3.4 | 文档摄入 | `service/DocumentIngestionService.java` |
| S2.3.5 | 启动自动摄入 | `config/DocumentIngestionRunner.java` |
| S2.4 | ChatService | `service/ChatService.java` |
| S2.5.1 | 输入护栏 | `guardrail/InsuranceUserInputGuardRailImpl.java` |
| S2.5.2 | 回复护栏 | `guardrail/InsuranceAssistantMessageGuardRailImpl.java` |
| S2.6 | ChatController | `controller/ChatController.java` |
| S2.7 | 缓存 | `service/CacheService.java` · `config/CacheConfiguration.java` |

---

## S2.9 阶段二与前序阶段的关系

```
阶段一 (spec1.md)
  ├─ 6 个 JPA Entity (Customer, Vehicle, Quote, Policy, Claim, PolicyDocument)
  ├─ 6 个 Repository
  ├─ DataInitializer (5 个种子用户 + 车辆)
  ├─ SecurityConfig (4 角色 HTTP Basic Auth)
  └─ LlmSelectionService (模型分层)
       │
       ▼
阶段二 (spec2.md) ← 本阶段
  ├─ ChatbotAgent (Agentic RAG 单动作)
  ├─ RagConfiguration (Lucene BM25 + ToolishRag)
  ├─ DocumentIngestionService (Markdown → Lucene)
  ├─ ChatService (会话管理)
  ├─ ChatController (POST /api/chat)
  ├─ Guardrail 体系 (输入 + 输出护栏)
  └─ CacheService (二级缓存)
       │
       ▼
阶段三 (spec3.md)
  └─ UnderwritingAgent ...
```

**本阶段新增依赖**：`embabel-agent-rag-lucene`、`embabel-agent-rag-tika`

# S3.流式回答
新增一个接口，允许用户获取流式回答。
