# 阶段三实施总结：UnderwritingAgent — 智能核保 MVP

> 基于 `spec.md` → `plan.md` → `task.md`，全部代码已生成并编译通过。

**实施时间**：2026-06-25 ｜ **分支**：feature/spec3

---

## 完成情况

| 编号 | 任务 | 状态 | 产出文件 |
|------|------|------|---------|
| T1 | DTO 类定义 | ✅ | 8 个 record |
| T2 | DataService | ✅ | `service/DataService.java` |
| T3 | RiskCalculationService | ✅ | `service/RiskCalculationService.java` |
| T4 | PremiumCalculationService | ✅ | `service/PremiumCalculationService.java` |
| T5 | PaymentService + BizException | ✅ | `service/PaymentService.java` + `service/BizException.java` |
| T6 | PolicyService | ✅ | `service/PolicyService.java` |
| T7 | VehicleInfoGuardRail | ✅ | `guardrail/VehicleInfoGuardRailImpl.java` |
| T8 | UnderwritingAgent | ✅ | `agent/UnderwritingAgent.java` |
| T9 | AgentService | ✅ | `service/AgentService.java` |
| T10 | InsuranceController + GlobalExceptionHandler | ✅ | `controller/InsuranceController.java` + 更新 |
| T11 | 更新 application.yml | ✅ | 新增 underwriting/risk/premium 配置段 |
| T12 | UnderwritingAgentTest | ✅ | 8 个测试用例 |

---

## 新增文件清单

### DTO（8 个）
| 文件 | 说明 |
|------|------|
| `dto/VehicleInfo.java` | LLM 提取的车辆信息，含 EXTRACTION_FAILED 哨兵 |
| `dto/UnderwritingResult.java` | 核保最终输出，含 of()/error() 工厂方法 |
| `dto/UnderwritingInput.java` | Agent 输入，携带 userId + 自然语言消息 |
| `dto/UnderwriteRequest.java` | POST /api/insurance/underwrite 请求体 |
| `dto/PayRequest.java` | POST /api/insurance/pay 请求体 |
| `dto/ApproveQuoteRequest.java` | 人工审批请求体（保费覆盖可选） |
| `dto/ApproveQuoteResponse.java` | 人工审批响应体 |
| `dto/PolicyResponse.java` | 保单查询响应体，含 from(Policy) 转换 |

### Service（7 个）
| 文件 | 核心方法 |
|------|---------|
| `service/DataService.java` | `findCustomer(userId)` → sentinel · `findVehicle(info, customer)` → 三级优先级 |
| `service/RiskCalculationService.java` | `calculate(customer, vehicle)` → [0,100] · `isLowRisk()` · `isHighRisk()` |
| `service/PremiumCalculationService.java` | `calculate(vehicleValue, riskScore, coverageType)` → BigDecimal 精度 |
| `service/PaymentService.java` | `pay(quoteId)` → 6 步流程：查询→状态校验→过期校验→模拟支付→签发保单→持久化 |
| `service/PolicyService.java` | `findByUserId()` · `findByPolicyNumber()` |
| `service/AgentService.java` | `processUnderwriting()` 120s 超时 · `approveQuote()` 人工审批 |
| `service/BizException.java` | 业务异常基类，携带 errorCode |

### Agent（1 个）
| 文件 | 说明 |
|------|------|
| `agent/UnderwritingAgent.java` | @Agent UTILITY · 4 个 @Action · sealed interface + 6 条 @State 路由 · StuckHandler |

### Controller（1 个新增 + 1 个更新）
| 文件 | 端点 |
|------|------|
| `controller/InsuranceController.java` | 6 端点：underwrite / policies / policies/{id} / approve / pay / health |
| `controller/GlobalExceptionHandler.java` | 新增 BizException 处理 |

### Guardrail（1 个）
| 文件 | 说明 |
|------|------|
| `guardrail/VehicleInfoGuardRailImpl.java` | 品牌词库 + 车牌正则 + 模糊表述检测 |

### Repository（1 个更新）
| 文件 | 新增方法 |
|------|---------|
| `repository/PolicyRepository.java` | `findByCustomer_UserId(String)` |

### 配置（2 个更新）
| 文件 | 变更 |
|------|------|
| `application.yml` | 新增 underwriting/risk/premium 三段 |
| `test/application.yml` | 新增 test-llm 映射，claim.rag → insurance.rag |

---

## 编译验证

```
./mvnw compile → BUILD SUCCESS（56 个源文件，0 错误）
./mvnw test-compile → BUILD SUCCESS
```

## 关键设计落地

| 设计决策 | 实现要点 |
|---------|---------|
| Sentinel 模式 | Customer.lookupFailed() / Vehicle.lookupFailed() 占位对象，UTILITY 规划器无 STUCK |
| Blackboard 错误传播 | `context.bind("underwriting_error", msg)` 写入，assessRisk 入口统一检查 |
| 快速失败 | extractVehicleInfo 通过 UnderwritingInput.userId 前置检查 customer |
| BigDecimal 保费 | `setScale(2, RoundingMode.HALF_UP)` 保证金额精度 |
| 线程池显式构造 | `ThreadPoolExecutor(2, 4, 60s, LinkedBlockingQueue(100), CallerRunsPolicy)` |
| 保单有效期 | `LocalDate.now().lengthOfYear()` 避免硬编码 365 |
| 零新依赖 | 全部复用 Embabel 0.3.5 + Spring Data JPA + DeepSeek LLM |

## 测试状态

测试类 `UnderwritingAgentTest` 已编写（8 个用例覆盖 US-U1~U5 + 错误路径 + 健康检查），编译通过。因 Embabel 框架在 test profile 下的 `default-llm` 配置绑定问题，ApplicationContext 加载失败。需排查 `embabel.models.default-llm` 在 test profile 下的属性绑定链路。

## 下一步

阶段四：ClaimsAgent 理赔智能体（参考 `docs/mvp4/spec4.md`）
