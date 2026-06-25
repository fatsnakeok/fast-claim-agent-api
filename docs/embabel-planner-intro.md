# Embabel 规划器介绍与项目选型

## 一、三种规划器

Embabel 框架提供三种规划器（`PlannerType`），由 `@Agent` 注解的 `planner` 属性指定：

| 规划器 | 模式 | 特点 | 适用场景 |
|---|---|---|---|
| **UTILITY** | 前向链（效用驱动） | 每个 `@Action` 携带 cost/value，框架自动构建依赖图，选效用最高的路径执行 | 动作间有明确的类型依赖关系，流水线式处理 |
| **GOAP** | 反向链（目标导向） | 声明 `@AchievesGoal` 目标，从目标反向搜索达成目标的动作序列 | 目标明确，但到达目标的路径不唯一，需要自动选择 |
| **SUPERVISOR** | 监督者 | 由外部协调器管理多个子 Agent | 多 Agent 协作场景 |

当前项目的两个 Agent 均使用 **UTILITY** 规划器。

## 二、`@State` 与 `@Action` 的关系

`@State` 是 UTILITY 规划器的**可选分支机制**，不是前置条件。

### 单一 @Action（无 @State）

```
ChatbotAgent
  └── @Action answerQuestion(UserInput) → ChatOutput
```

UTILITY 扫描 Agent 类，发现只有 1 个动作，执行图退化为直线，直接执行后结束。规划的开销为 O(1)。

### 多 @Action + @State 分支

```
UnderwritingAgent
  ├── @Action extractVehicleInfo → VehicleInfo
  ├── @Action lookupCustomer     → Customer
  ├── @Action lookupVehicle      → Vehicle
  └── @Action assessRisk         → UnderwritingDecision (@State sealed interface)
                                        ├── LowRiskQuote.execute()      → UnderwritingResult
                                        ├── MediumRiskReview.execute()  → UnderwritingResult
                                        ├── HighRiskDecline.execute()   → UnderwritingResult
                                        ├── CustomerNotFound.execute()  → UnderwritingResult
                                        ├── VehicleLookupError.execute()→ UnderwritingResult
                                        └── ExtractionFailed.execute()  → UnderwritingResult
```

**路由机制**：

1. 框架通过反射获取每个 `@Action` 的入参类型和返回类型
2. 当 `assessRisk()` 返回 `@State` sealed interface 的子类型实例时，框架自动匹配该子类型内部的 `@Action` 作为下一步执行目标
3. 框架将方法入参与实际返回值按类型兼容性进行匹配，构建执行依赖图
4. `resolveStateInstance()` 从 Blackboard 中取出当前状态实例，调用对应 record 的 `@Action` 方法

sealed interface + 穷举 record 的模式让框架能在**编译期检查所有分支是否被覆盖**。

## 三、为什么选择 UTILITY 而非 GOAP

两个 Agent 都没有使用 GOAP，原因各不相同：

### UnderwritingAgent — 前向流水线，无路径选择

核保流程严格有序：提取车辆信息 → 查找客户 → 查找车辆 → 风险评估。每一步的输出是下一步的输入，由类型依赖决定，排列唯一。GOAP 的反向搜索（"要达到核保结果，需要哪些前提条件？"）在这里没有选择余地，只会浪费计算。

分支发生在流水线末端（低/中/高风险 + 3 种错误），直接用 `@State` sealed interface 路由即可，不需要 GOAP 的目标匹配。

### ChatbotAgent — 单动作，谈不上规划

只有一个 `@Action`，执行图是单节点。GOAP 的 `@AchievesGoal` + 反向搜索完全是空转。

### 选型原则

- **顺序明确、由类型依赖自然决定** → UTILITY
- **目标明确、但到达路径不唯一** → GOAP
- **多 Agent 协作** → SUPERVISOR

## 四、UTILITY 如何自动推导执行图

UTILITY 不靠显式声明执行顺序，而是通过**类型依赖自动推导**：

1. 扫描 Agent 类中所有 `@Action` 方法，记录每个方法的入参类型列表和返回类型
2. 构建有向图：如果方法 A 的返回类型与方法 B 的某个入参类型兼容，就存在 A→B 的边
3. 方法参数值从 Blackboard 中按类型解析（`getValue(variable, type)`），匹配逻辑支持类型名、继承关系、`Aggregation` 聚合
4. 每个 `@Action` 携带 cost/value 属性，UTILITY 规划器选择净效用（value - cost）最高的路径执行
5. 当只有一个动作时，图退化为单节点，直接执行