# 金字塔彻查法 — 从现象到根因的链路级排障方法论

> 从 2026-08-04 流式传输彻查中提炼。
> 适用于任何"修了很多次但从未修好"的功能问题:流式输出、会话持久化、删除清理……
> 与 `audit-methodology.md`(问"功能是否闭环")互补——本方法问"现象为什么发生"。

---

## 一句话定义

从**不可辩驳的现象事实**出发,把功能链路逐层拆成金字塔,每层用**代码证据**验证或排除,直到根因落地。

核心信念:**修复前第一件事是确认这层真的是坏的。** 盯着同一层反复修、每次都"觉得自己找到了根因",往往是该层本来健康,真凶在别处。

---

## 五步法

### 1. 定现象 — 只记事实,不下结论

写下可观测行为本身,不带任何归因:

- ❌ "流式没生效,因为 SSE 解析格式不对" ← 已下结论,会带偏调查
- ✅ "用户观察到:答案永远整段一次性出现,全程显示'思考中…',打字机效果从未出现"

### 2. 拆链路 — 整条链按环节分层

把数据从源头到展示的完整路径拆成独立叶子,每个叶子是一层:

```
请求构建 → 网络传输 → SSE 解析 → 引擎回调 → ViewModel 缓冲 → 状态桥接 → UI 渲染
```

每层都是独立嫌疑,互不牵连。分几层派几个独立视角去查(独立代理互不共享结论,防互相污染)。

### 3. 逐层提问 — 每层都要证据

对每层问:**"这层有没有机制上能解释现象的问题?"**

- 要求**代码证据**:`file:line` + 代码摘录,不许"感觉"、"可能"
- **健康层也要明示"已排除"**——否则下次排障又从头查一遍,历史结论随之蒸发
- 死代码是铁证:只赋值从不读取的变量 = 设计意图从未落地(例:本项目 `sawActionMarker`)

### 4. 交叉验证 — 多视角结论一致才锁定

- 按链路分段派独立代理,各自独立得出"最可疑 3 点"
- **两份独立报告指向同一处才锁定根因**;同一层被两批人各修一次还没好,立即换层
- 根因必须能**机制性解释全部现象**(不是解释一个侧面)

### 5. 修复后回测 — 并记住修过哪里

- 修复后真机/实测确认现象真的消失
- 记下"曾经修过、证明健康"的层,防止复发时重蹈覆辙

---

## 反模式 — 本项目真实教训

**盯着同一层反复修**:流式输出三次修复全部落在 SSE 解析层(OpenAI 格式 → Anthropic 双格式 → 细节修补),但该层**从头到尾是健康的**——证据:引擎在调它、token 在流出、StateFlow 在传递。真凶在 ViewModel 的显示过滤器,之前从未有人查过。

> 反复失败的信号:**两次以上在同一区域"找到根因"却没修好** → 立即金字塔展开全链路,而不是在原有区域找第三次。

---

## 实战案例 — 2026-08-04 流式传输彻查

### 链路(金字塔分层)

```
AdaptiveLlmProvider.consumeSseStream → AgentEngine.runReActLoop
→ AgentViewModel.onDelta → session.messages(StateFlow) → 桥接
→ MainScreen LazyColumn → AgentBubbleWithTrace → MarkdownText
```

### 判定健康(已排除,勿再修)

| 层 | 证据 |
|----|------|
| Provider 实例化 | 主链路是 AdaptiveLlmProvider(AppRoot.kt applyConfiguration → AgentSessionFactory.kt:69/73),流式 override 存在 |
| SSE 解析 | consumeSseStream 双格式解析(OpenAI choices[0].delta + Anthropic delta.text)正确,fullContent 累积完整 |
| 引擎透传 | AgentEngine.kt:575-579 每轮迭代都调 completeStreamingWithMessages 并传 onDelta |
| 状态桥接 | session.messages collect → _messages,StateFlow 最终值必达 |
| UI 渲染 | MarkdownText remember(content) 按内容重解析,无陈旧缓存;ViewModel 单实例无分叉 |

### 锁定根因(3 个,按严重度)

**① 脏缓冲 + hasAction 锁存(最强)——AgentViewModel.kt:449-465**
- `streamBuf` 整个 run 生命周期只 append、从不清空;`hasAction = text.contains("Action:")` 是对**全量历史缓冲**做子串匹配
- 工具型代理第 1 步必现 `Action:` → `hasAction` 永久为 true
- 最终答案若按 parse Rule 3 输出纯文本(无 "Final Answer:" 前缀——PromptEngine.kt:654-667 明确支持),则 `hasFinal=false` + `hasAction=true` → `displayText=""` → **整段流式答案被丢弃**,UI 全程"思考中…",结束时整段弹出
- 铁证:`sawActionMarker` 只赋值从不读取(死代码),"工具轮后恢复显示"的设计意图从未落地

**② 节流无结束 flush——AgentViewModel.kt:453-456**
- 50ms 节流只在 delta 到达时检查;流尾段 token 落在窗口内则永不增量推送,只会在引擎返回后的最终 replace 中一次性出现

**③ 模式分发绕过流式——AgentViewModel.kt:489-519 + ComplexityDetector.kt:12-42**
- `detectComplexity` 自动把评分 >4 的普通任务(如"帮我写脚本批量下载文件")静默升级 GOAL/MISSION
- GOAL 经 runWithGoal → GoalModeExecutor.kt:56-60 调 runReActLoop **不传 onDelta** → 引擎静默降级非流式(AgentEngine.kt:577-579)
- MISSION/SWARM/PLAN 路径完全无 onDelta 参数;fallback provider(RemoteApi)同样丢流式

### 观察结论

引擎层与 SSE 解析层**无缺陷**;问题全部集中在 ViewModel 的过滤/节流/分发三层。之前所有"SSE 解析修复"都打在了健康层上——这就是"永远修不好"的结构性原因。

---

## 使用时机

| 场景 | 做法 |
|------|------|
| 功能"修了很多次还没好" | 立即金字塔展开,先确认每层真的坏没坏 |
| 新功能首验不通过 | 沿链路逐层打点(日志),从源头往下找断点 |
| 回归问题(以前好过现在坏了) | 金字塔 + git log 对比行为变更,变更层优先 |
| 任何"我明明改过了怎么还在" | 先查证据(死代码、未被调用的分支),再动手 |

---

*方法论版本: 0.1.0 | 提炼自 2026-08-04 流式传输彻查(v0.28.2)*
