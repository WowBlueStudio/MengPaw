# 金字塔彻查法 — 从现象到根因的链路级排障方法论

> 从 2026-08-04 流式传输彻查中提炼；0.2.0 补充证据等级与 2026-08-09 两个实战案例。
> 适用于任何"修了很多次但从未修好"的功能问题:流式输出、会话持久化、删除清理……
> 与审计方法论 `bug-audit-methodology`（记忆，问"功能是否闭环"）互补——本方法问"现象为什么发生"。

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

### 3.5 证据等级 — 运行时证据优先于静态推断

同一层可以被不同证据强度的判断同时支撑和推翻,排序如下:

1. **运行时证据** (崩溃堆栈 / logcat / dropbox / 实际输出) — 最高,直接记录"发生了什么"
2. **动态行为验证** (加日志打点 / 最小复现 / 条件开关) — 主动制造可观察行为
3. **静态代码推断** ("这段代码看起来会/不会 X") — 依赖框架默认行为与版本,可能系统性错
4. **记忆与假设** ("以前遇到过 / 大概率是") — 只作线索,不作结论

> 反例(2026-08-09 实证): 排查链接点击闪退时,静态读 `MarkdownText` 代码推断
> "没有 LinkInteractionListener → 链接点击无反应 → 闪退在别处",把嫌疑指向
> FileProvider 映射。运行时 dropbox 堆栈直接推翻: `TextLinkScope →
> AndroidUriHandler.openUri → ACTION_VIEW(file://)` — **新版本 Compose 对
> LinkAnnotation.Url 有默认点击处理**,静态"看起来没处理"与实际行为相反。
> 结论: 框架默认行为随版本变化,**静态推断必须用运行时证据校准**。

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
- `detectComplexity` 自动把评分 >4 的普通任务(如"帮我写脚本批量下载文件")静默升级 GOAL/SWARM (v0.34.4 Mission 并入 Swarm)
- GOAL 经 runWithGoal → GoalModeExecutor.kt:56-60 调 runReActLoop **不传 onDelta** → 引擎静默降级非流式(AgentEngine.kt:577-579)
- SWARM/PLAN 路径完全无 onDelta 参数;fallback provider(RemoteApi)同样丢流式

### 观察结论

引擎层与 SSE 解析层**无缺陷**;问题全部集中在 ViewModel 的过滤/节流/分发三层。之前所有"SSE 解析修复"都打在了健康层上——这就是"永远修不好"的结构性原因。

---

## 实战案例 — 2026-08-09 链接点击闪退 (FileUriExposedException)

### 链路(金字塔分层)

```
Markdown 渲染(design-system MarkdownText)
→ 链接注解(LinkAnnotation) → 点击处理(Compose TextLinkScope/默认 UriHandler)
→ Intent 启动(ACTION_VIEW file://) → Android StrictMode → 进程崩溃
```

### 证据过程

| 层 | 判定 | 证据 |
|----|------|------|
| Markdown 渲染 | 健康(已排除) | commonmark 解析 + 安全偏移,无异常面 |
| 链接注解 | **初始误判** | 静态读代码以为 `LinkAnnotation.Url` 无点击处理(旧版本行为);运行时堆栈显示新版本有默认 `TextLinkScope` 处理 |
| 点击处理 | **根因** | 崩溃堆栈 `AndroidUriHandler.openUri` 对 `file://` 起 `ACTION_VIEW` → `FileUriExposedException` |
| Intent 启动 | 根因延续 | file:// 未走 FileProvider,Android 7+ 安全异常 |

### 锁定根因与修复

Compose 默认 UriHandler 直接对 file:// 起 ACTION_VIEW。修复: 链接改用
`LinkAnnotation.Clickable` 自定义处理 — http(s) 直接 ACTION_VIEW; 本地路径去
`file://` 前缀经 FileProvider 转 `content://` 再抛系统选择器; 目标不存在/打开
失败给 Toast。`file_paths.xml` 补输出目录映射。

### 教训

- **运行时堆栈 > 静态推断**: "代码里没有 handler" 的静态结论被运行时行为推翻。
- 同一现象(点击崩溃)的根因链上,静态排查先锁定 FileProvider(健康层),
  运行时证据才锁定 Compose 默认处理(真凶层)。

---

## 实战案例 — 2026-08-09 SessionShellPoolTest 全量 flaky

### 现象

`timed out command destroys session` 单独跑全绿,`./gradlew test` 全量下稳定失败
(恢复命令也报 "Command timed out (0s)")。

### 链路

```
测试用例(sleep 3 + 500ms 超时) → 超时销毁会话
→ 恢复命令(echo)借新会话 → 新 sh 进程启动 + 管道初始化 → 执行
```

### 根因

500ms 超时窗口只覆盖"被测试命令"耗时,没覆盖"超时销毁后重建会话"的进程启动
成本;全量并行(`org.gradle.parallel=true` + 多模块同时跑)放大启动抖动,恢复命令
借新会话时启动即超时。修复: 超时窗口 500 → 1500ms(仍 < `sleep 3`,语义不变)。

### 教训

- **单独跑绿 ≠ 全量绿**: 涉及真实子进程(进程/ADB/外部工具)的测试,超时窗口
  必须 ≥ 进程启动成本 3 倍,且要在全量并行负载下验证。
- "环境抖动"不是免修理由: 全量下稳定复现 = 测试自身时序缺陷,必须修测试。

---

## 使用时机

| 场景 | 做法 |
|------|------|
| 功能"修了很多次还没好" | 立即金字塔展开,先确认每层真的坏没坏 |
| 新功能首验不通过 | 沿链路逐层打点(日志),从源头往下找断点 |
| 回归问题(以前好过现在坏了) | 金字塔 + git log 对比行为变更,变更层优先 |
| 任何"我明明改过了怎么还在" | 先查证据(死代码、未被调用的分支),再动手 |
| 崩溃类问题(闪退/ANR) | 先拿运行时堆栈(dropbox/logcat),再静态读码 — 堆栈会直接告诉你真凶层 |
| 测试单独跑绿但全量挂 | 测试时序/共享状态缺陷 — 全量并行负载下验证,超时窗口留足启动成本 |

---

*方法论版本: 0.2.0 | 提炼自 2026-08-04 流式传输彻查(v0.28.2) + 2026-08-09 链接闪退/测试 flaky 彻查(v0.34.3)*
