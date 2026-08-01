# 火种模式（Swarm Mode）设计文档

> 版本: 0.1.0 | 状态: 已实现（内核 v0.25+） | 命名释义: **星星之火，可以燎原**

## 1. 背景

借鉴 Kimi Agent Swarm（蜂群模式）为 MengPaw 增加"多 Agent、混合模型、复合型任务"能力。Kimi 的编排能力训练进模型权重（PARL 强化学习），MengPaw 与之相反，走工程编排路线（同 kimi-cli 开源实现）——编排逻辑在内核代码，用户可完全掌控。

**核心问题**（本项目立项时的探讨结论）：
- 编排是否只加内核 API 即可实现，不内置插件？→ **是**。编排是纯 JVM 逻辑，与 Goal/Fleet 同策略（开发文档 §3.5：Loop 模式已内置 AgentEngine，不再作为独立插件）。工具面（fs/net/skill/browser 等命令）继续由插件提供，跨设备 Agent（Tribe/连接器）继续在插件层——分工不变。
- 混合不同模型是否可行？→ **零障碍**。`LlmProvider` 是接口，`AdaptiveLlmProvider(endpoint, key, model)` 可任意组合。缺的只是编排层按角色注入 provider 的参数化——即本设计的 `roles` 参数。

## 2. 借鉴来源

### 2.1 Kimi Agent Swarm（四层结构）

```
L1 协调器     拆解任务 → 路由到异构技能槽 → 屏障等待关键子任务 → 失败恢复
L2 异构技能槽  搜索 / 深研 / 写作 / 建站
L3 子 Agent   独立上下文，短而专（共享总预算 ÷ 子任务数 ≈ 每任务 13 步）
L4 合成层     跨格式一致性合成
```

工程要点：**上下文分片**（每个 worker 只看自己的切片，只回报关键结论——对比被动压缩，"主动式上下文管理"）；**共享总预算**（4000 步是整个蜂群的预算，不是每 Agent 上限）；**子 Agent 临时化**（执行完丢弃，无跨任务记忆）；**通信单向**（子 Agent 之间不互通，只回报父级——协调器是唯一枢纽）。

### 2.2 丰田 JIT（四机制映射）

| JIT 概念 | 蜂群映射 | 本实现 |
|---|---|---|
| 看板 WIP 上限 | 并行 worker 数上限 | `maxParallel`（Semaphore） |
| 均衡生产/共享预算 | 总步数预算 | `maxTotalSteps`（SwarmBudget，AtomicInteger CAS） |
| 安灯（Andon）失败即停线 | worker 失败回报协调器决策 | `andonDecision()`（重派/终止，不静默重试） |
| 看板卡片 | 结构化结果回报 | `SwarmResultCard`（协调器只收卡片不收日志） |
| 零库存 = 零待命 Agent | worker 用完即销毁 | 独立 Session `scope="swarm"` + `deleteSession` |
| SMED 快速换模 | 轻 worker（创建/销毁足够便宜） | 复用全局 Pipeline，不建完整 AgentEngine |

**反向借鉴（不能学）**：JIT 的"单供应商依赖"脆弱性 → 蜂群反而要**供应链多元化**：多 provider 角色化 + `AdaptiveLlmProvider` fallback 链 + Andon 重派切 `worker.alt` 模型。

## 3. 架构

### 3.1 内核 API

```kotlin
suspend fun AgentEngine.runWithSwarm(
    task: String,
    roles: Map<String, LlmProvider> = emptyMap(),   // planner/worker/verifier/synthesizer/worker.alt 可异模型
    maxSubtasks: Int = 5,
    maxParallel: Int = 4,                           // WIP 闸
    maxStepsPerSubtask: Int = 12,                   // 单任务闸
    maxRetriesPerSubtask: Int = 2,
    maxTotalSteps: Int = maxSubtasks * maxStepsPerSubtask,  // 总预算闸
    onStep: ((AgentEngine.TraceStep) -> Unit)? = null
): String
```

角色解析：`roles[role] ?: roles["worker"] ?: 引擎主 provider`；Andon 重派时优先 `roles["worker.alt"]`（可不同模型）。

### 3.2 文件布局

| 文件 | 职责 |
|---|---|
| `kernel/agent/SwarmTypes.kt` | SwarmSubtask / SwarmResultCard / SwarmBudget |
| `kernel/agent/SwarmModeExecutor.kt` | 火种执行器（四阶段） |
| `kernel/AgentEngine.kt` | `runWithSwarm` 入口 + `runWithFleet` 转发 + internal 访问器 |
| `kernel/session/History.kt` | `createSession`/`deleteSession` 加 `@Synchronized`（并发 CAS 修复） |
| `kernel/cli/CommandExecutor.kt` | `ExecutionContext.scope` 字段（"swarm" 屏蔽记忆写入） |
| `kernel/agent/AgentMemoryExecutor.kt` | 写入命令 scope 检查 |
| `kernel/llm/PromptEngine.kt` | 系统提示词火种模式 section |

### 3.3 执行流程

```
runWithSwarm:
  Phase 0 规划器: decompose (JSON + role 字段; 失败→行解析回退; 空→单 Agent 兜底)
  Phase 1+2 并行: Semaphore(maxParallel) + async(KernelDispatchers.BACKGROUND)
        每子任务: runWorker(独立 Session scope=swarm) → Andon 决策 → verify (VERDICT/ANALYSIS/FIX)
  Phase 3 合成器: 汇总卡片 → 最终报告 ("## 火种模式: ...")
```

### 3.4 轻量 Worker（零待命/SMED）

Worker **不建完整 AgentEngine**（主循环 `runReActLoop` 耦合 ~15 个共享可变字段，并行复用会状态竞争——会话污染只是表象，并发安全才是根因）。SwarmModeExecutor 内实现 ~50 行轻量 ReAct 循环，复刻 `PlanModeExecutor.executePlanStep` 已验证的 API 组合：

- 复用：全局 Pipeline（`pipelineManager.buildPipeline()`）+ `promptEngine.parse` + `agentEngine.buildConversation(sessionId)`
- 独立：Session `scope="swarm"`（不入 `conversationSessionId`），`finally { deleteSession }` 销毁
- 刻意不含：`_state`/`_output` 写入、`detectLoop`/`trackResult`（共享状态）、EvolutionHook/checkpoint/上下文折叠
- 命令 60s 超时；observation 截断 4000 字符防上下文膨胀

### 3.5 并发安全清单

- `SwarmBudget.tryConsume()` AtomicInteger CAS，无锁并行安全
- `createSession`/`deleteSession` 均 `@Synchronized`（CAS 交换在并行 worker 下会丢更新/复活会话——两个真实缺陷已修复）
- worker 不写 `conversationSessionId`；协调器主协程独占更新 AgentState
- worker 的 LLM 异常 try/catch 转 `WorkerOutcome(error)`，不炸掉 `awaitAll`
- `attachRunningJob` + `ensureActive`（Job.isActive）双保险，`stop()` 可达

## 4. 十二问审计结论（docs/audit-methodology.md）

| 缺口 | 判定 | 修复 |
|---|---|---|
| 系统提示词无火种模式 | P0 | PromptEngine 中英双语 section（能力简介/适用场景/触发方式） |
| UI 无触发入口 | P0 | `LoopMode.SWARM` + AgentViewModel 分发（设置页 entries 自动出现） |
| worker 记忆污染 | P1 | `ExecutionContext.scope="swarm"` → `agent.memory.*` 写入命令屏蔽 |
| 多模型配置 UI（roles 来源） | P2 | 设置页角色→模型映射 + Vault 多 key（后续） |
| 并行实时进度 UI | P2 | FleetMonitorOverlay 接入 worker 卡片流（后续） |

## 5. 边界与后续

- **不在本设计内**：跨设备多 Agent（Tribe/ACP/连接器继续插件层）；roles 的 UI 配置来源；实时进度监控
- **适用场景**：大规模检索、批量处理、多视角复合任务（Kimi 官方结论一致：强顺序依赖任务、频繁写全局状态的任务不适合）
- **预算语义**：`maxTotalSteps` 计 worker 实际 LLM 轮数；decompose/synthesize 属协调器开销不计入
