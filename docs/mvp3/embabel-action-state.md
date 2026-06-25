# @Action / @State / @AchievesGoal 协作机制

> 以 UnderwritingAgent (PlannerType.UTILITY) 为例

## @Action 执行顺序：类型依赖图自动推导

UTILITY 规划器通过**参数类型和返回类型的匹配关系**自动构建依赖图，不靠显式排序。

UnderwritingAgent 的 4 个 `@Action` 签名：

```
extractVehicleInfo(UnderwritingInput, OperationContext) → VehicleInfo
lookupCustomer    (UnderwritingInput, OperationContext) → Customer
lookupVehicle     (VehicleInfo, Customer, OperationContext) → Vehicle
assessRisk        (Customer, Vehicle, OperationContext) → UnderwritingDecision
```

框架的连线规则：如果方法 A 的**返回类型**能匹配方法 B 的**某个入参类型**，就存在 A→B 的依赖边。

推导结果：

```
UnderwritingInput ──┬──→ extractVehicleInfo ──→ VehicleInfo ──┐
                    │                                          ├──→ lookupVehicle ──→ Vehicle ──┐
                    └──→ lookupCustomer ──→ Customer ──────────┘                                ├──→ assessRisk
                                                                                                 │
                                                    Customer ────────────────────────────────────┘
```

- `extractVehicleInfo` 和 `lookupCustomer` 都只需要 `UnderwritingInput` → **可并行执行**
- `lookupVehicle` 需要 `VehicleInfo` + `Customer` → **等前两步都完成**
- `assessRisk` 需要 `Customer` + `Vehicle` → **等第2、3步完成**

## @State：运行时分支分发，非顺序

`@State` 标记在 sealed interface 上，声明"这个类型的所有子类型都是可能的执行分支"。

```java
@State
public sealed interface UnderwritingDecision permits
    LowRiskQuote, MediumRiskReview, HighRiskDecline,
    CustomerNotFound, VehicleLookupError, ExtractionFailed {
}
```

当 `assessRisk()` 返回 `new LowRiskQuote(...)` 时，框架的调度流程：

```
1. 拿到返回值实际类型 → LowRiskQuote
2. 反射检查 UnderwritingDecision.permits → 6个候选
3. 匹配到 LowRiskQuote.class
4. 执行 LowRiskQuote 的 @Action + @AchievesGoal 方法
```

这不是执行顺序，是运行时的**类型匹配分发**，类似多态但由 Embabel 框架驱动。6 条分支互斥，只会进入匹配的那一条。

## @AchievesGoal：终止信号

`@AchievesGoal` 标记**终结动作**——执行完此方法后 Agent 流程终止，返回值交给调用方。

```java
// AgentService 的调用端
AgentInvocation.on(agentPlatform)
    .returning(UnderwritingResult.class)  // ← 声明需要这个类型
    .invoke(input);
```

框架内部循环：

```
for each @Action in dependency graph:
    执行 action
    if action 有 @AchievesGoal:
        拿到返回值 → Agent 终止 → invoke() 返回 → completingFuture 完成
    else:
        继续下一个 action
```

6 个 `@State` 子类型的 `execute()` 全都有 `@AchievesGoal`，所以无论走哪条路径，都会在此终止并返回 `UnderwritingResult`。

## 三者关系总结

| 注解 | 角色 | 决定因素 |
|------|------|---------|
| `@Action` | 流水线步骤 | 入参/返回类型决定依赖图顺序 |
| `@State` | 分支标记 | 运行时返回值类型决定进入哪条分支 |
| `@AchievesGoal` | 终止点 | 标记最终产出，执行后 Agent 退出 |
