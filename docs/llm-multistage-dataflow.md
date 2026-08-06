# LLM 多阶段输出数据流：框架各环节实际收到的内容

> 问题：LLM 调用工具进行多阶段的输出时，框架到底收到了哪些内容？
> 本文从 `AgentEngine.run` ReAct 循环出发，逐环节列出实际收到的内容与去向。
> 源码锚点：`AgentEngine.kt`（run/buildConversation）、`PromptEngine.kt`（parse）、`History.kt`（getStructuredHistory）、`AgentViewModel.kt`（UI 消费）。

---

## 1. 一回合全景（step 的完整数据流）

```
用户任务
  │  (user 消息入历史)
  ▼
① LLM 收到   = 系统提示词(当前模板) + 历史(含上轮原始 ReAct 文本 + Observation 块)
  │           + 可选注入：进化省察引导(系统角色) / 中断恢复块(user 前缀)
  ▼
② LLM 输出   = 一段完整文本（ReAct 格式，可含多个 Action 批）
  │
  ├─ onDelta  = 原始增量 token（逐批透传 UI 播放器，打字机观感）
  ├─ 返回值   = 完整文本（同一内容，供解析）
  ▼
③ 框架收到   = sanitize 后的完整文本（sanitized）
  │
  ├─ 入库     = assistant 消息：原始 ReAct 文本（含 Thought/Action/Action Input 全部）
  ├─ parse    = 解析为结构化 ReActResponse（见 §4）
  ▼
④ 执行       = 去重 → 拼命令行 → 参数门卫 → 循环检测 → 并行执行(60s 超时)
  ▼
⑤ Observation 组装（见 §6）
  │
  ├─ onStep   = TraceStep(step, thought, action, observation) → UI trace 行
  └─ 入库     = assistant 消息：`Command: X\nResult: Y`（多条 Observation 合并一条）
  ▼
⑥ 下一轮：LLM 收到 ③④ 入库的两条 assistant 消息，继续循环
```

## 2. LLM 收到的内容（buildConversation，`AgentEngine.kt:944`）

组装顺序（`llmRequestBuilder.buildMessages`，含缓存注解）：

| 位置 | 角色 | 内容 |
|------|------|------|
| 首条 | system | `currentSystemPrompt`（当前模板，TEMPLATE_HASH 防静默旧词） |
| 末尾追加 | system | 进化省察引导（金字塔提问片段，限流 MAX_INJECTIONS/会话；**末尾追加不前插**——前插击穿前缀缓存） |
| 用户消息 | user | 原样（附件挂二进制键） |
| 工具轮 | assistant | **原始 ReAct 文本**（含 Thought/Action/Action Input——LLM 看到自己的完整思维过程） |
| 工具轮 | assistant | **Observation 块**（`Command: X\nResult: Y`，多条合并一条消息） |
| 中断恢复 | user 前缀 | `buildInterruptedRecoveryBlock`（上轮中断时注入已完成工具摘要） |

关键事实：
- **Observation 以 assistant 角色入库**（非 tool/user 角色）——`AgentEngine.kt:776`
- **最终答案轮入库两条 assistant**：原始 ReAct 文本（`:643`）+ 纯答案（`:658`）
- 任务本身是 user 消息，走历史，不额外包装

## 3. LLM 输出的内容

单轮输出是一段自由文本，框架容忍以下形态（提示词要求 ReAct 格式，但解析不假设）：

```text
Thought: 用户要查电量，先看设备状态。
Action: sys.battery
Action Input:
```

**多 Action 批**（提示词允许"多个独立工具一次输出，框架并行执行"）：

```text
Thought: 需要三个独立信息。
Action: sys.battery
Action Input:
Action: sys.network
Action Input:
Action: sys.storage
Action Input:
```

**最终答案**：

```text
Thought: 信息齐全了。
Final Answer: 你的电量是 85%……
```

**非 ReAct 模型**（无任何标记的自然回复）→ 框架按最终答案处理。

## 4. parse 解析规则（`PromptEngine.kt:540`）

标记定位：`Final Answer:` 全文匹配（大小写不敏感，中英冒号）；`Action:` **只认行首**（P2 修复：全文匹配会误切 Action Input JSON 内的 "action:" 字样）。

| 规则 | 条件 | 结果 |
|------|------|------|
| Rule 1 | 有 `Final Answer:` 且在其后无 Action（或 Action 数 < 2） | `ReActResponse(finalText, isFinal=true)` |
| Rule 2 | 有 `Action:`（含多 Action） | 按 Action 位置切段解析，并行执行 |
| Rule 3 | 无任何标记 | 视为最终答案（非 ReAct 模型自然回复） |
| needsContinue | 只有 Thought 无 Action | 注入 `继续。输出 Action: <命令>…` user 消息重试（连续 2 次强制收尾） |

参数解析（`Action Input`）：
- 空 / 字面 `{}` → `emptyMap()`（无参命令两形态统一，不把 `{}` 当真实参数）
- `{...}` JSON 对象 → 字段 map
- 其他 → `{"raw": 原文}`（CLI 纯文本参数）

## 5. 执行环节收到的内容（`AgentEngine.kt:697-735`）

1. `actionList = parsed.actions`（去重：同命令同参数只执行一次——模型偶发重复输出同一 Action）
2. 命令行 = `"${name} ${参数空格拼接}"`（JSON 参数双轨制：`ToolCall.paramFormatError()` 门卫——JSON 形态不被 CLI 误解析，命中即返回 PARAM_FORMAT_ERROR 不执行）
3. `detectLoop`：同命令 5 次窗口重复 → 终止（安全命令豁免）
4. 并行执行：`async(BACKGROUND) { withTimeout(60s) { pipeline.execute(cmd, context) } }`
5. 超时 → `命令超时 (60s)…` 错误结果

## 6. Observation 组装（框架写给 LLM 的"结果"）

| 结果 | 框架收到/写入的内容 |
|------|---------------------|
| 成功 | `result.output`（命令原始输出文本） |
| 失败 | `Error [错误码]: 错误文本`（错误码注入——PARAM_FORMAT_ERROR/NETWORK_OFFLINE/…，模型可见） |
| 剪枝 | `toolResultManager.pruneToolResult`（长结果按预算截断，防上下文膨胀） |

多 Action 批：每条 `Command: X\nResult: Y` 独立成段，**合并为一条 assistant 消息**入库（`\n\n` 分隔）；`onStep` 逐条回调（thought 只在第一条 Action 上携带，后续空——UI 渲染纯工具行防重复）。

## 7. 边界与防御

| 场景 | 框架行为 |
|------|---------|
| 空响应（SSE 零增量） | 重试一次；仍空 → 写明确错误 + 终止 + 事件上报 |
| 连续 5 次失败 | 终止（失败循环防护） |
| 命令循环（同命令 5 次） | 终止（LOOP_DETECTED） |
| 50 步上限 | 自适应扩展至 1.5×（仍在产出时），最终强制收尾 |
| 中断回合 | 恢复块注入（已完成工具摘要给下一轮 LLM） |

## 8. 各执行模式差异

| 模式 | 工具轮流式（onDelta） | 呈现 |
|------|:---:|------|
| REACT（主链路） | ✅ | 完整流式 + 工具提前通知 + trace 行 |
| GOAL | ✅ | 同主链路（`runWithGoal` 同签名透传） |
| MISSION/FLEET | 合成阶段 ✅ / worker 阶段 ❌ | 并行 worker 非流式（设计选择），onStep/traces 呈现进度 |
| SWARM | worker 阶段 ❌ | 同上，Andon 协议呈现 |
| PLAN | ✅ | 同主链路 |

## 9. 完整示例（三层收到的内容对照）

用户：`我的手机电量多少？`

**Step 1 — 工具轮**：

```
LLM 输出（完整文本）:
Thought: 用户问电量，需要查询系统电量。
Action: sys.battery
Action Input:

框架 parse 收到:  ReActResponse(thought="用户问电量…", actions=[ToolCall("sys.battery", {})])
执行器收到:       "sys.battery"（CommandRegistry 查 sys 命名空间 → BatteryPowerExecutor）
执行器返回:       ExecutionResult(success=true, output="电量 85%，充电状态：未充电，温度 31.2°C")

Observation 组装: "Command: sys.battery\nResult: 电量 85%，充电状态：未充电，温度 31.2°C"
入库:            [assistant] Thought: 用户问电量，需要查询系统电量。\nAction: sys.battery\nAction Input:
                 [assistant] Command: sys.battery\nResult: 电量 85%，充电状态：未充电，温度 31.2°C

UI 流式收到(onDelta): Thought: 用户问电量，需要查询系统电量。\nAction: sys.battery\nAction Input:
UI trace 收到(onStep): TraceStep(1, "用户问电量…", "sys.battery", "电量 85%…")
```

**Step 2 — 最终答案轮**：

```
LLM 收到:  system 提示词 + [user 任务] + [assistant 原始 ReAct] + [assistant Observation] + [user 继续对话历史]
LLM 输出:  Thought: 电量信息已拿到，直接回答。\nFinal Answer: 你手机当前电量 85%，未在充电。
框架 parse: ReActResponse("你手机当前电量 85%，未在充电。", isFinal=true)
入库:      [assistant] Thought: 电量信息已拿到，直接回答。\nFinal Answer: 你手机当前电量 85%，未在充电。
           [assistant] 你手机当前电量 85%，未在充电。   ← 纯答案（UI 最终显示用）
```

**历史继续累积**——用户下一句提问时，以上全部消息（含两轮 assistant）原样发给 LLM。

---

## 附：调试入口

- 流式增量观察点：`AgentViewModel.onDelta`（`MengPawLatency T3 first-delta` 日志）
- 完整文本观察点：`AgentEngine.kt:643` 入库前（`postResult.text`）
- 解析产物观察点：`PromptEngine.parse` 返回值
- Observation 组装观察点：`AgentEngine.kt:754-764`
- UI 侧最终消息 = 入库的纯答案（Final Answer 后内容），工具轮历史 = 原始 ReAct 文本（SessionManager 可查）
