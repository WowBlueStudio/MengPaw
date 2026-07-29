# MengPaw 开发文档

> 📄 灵感来源: [ATTRIBUTIONS.md](ATTRIBUTIONS.md) — QwenPaw · Hermes · OpenClaw · Claude Code · ReAct · ComfyUI · LangChain · CrewAI · Dify · Tavily · Arco Design · Material Design 3

> **版本**: 0.18.3 | **更新**: 2026-07-29 | **架构**: 微内核(54文件) + AgentRuntime + 25插件(内置版随壳更新) + 三轨记忆 + BM25命令检索(self.search) + 三层自适应调度(REACT/GOAL/MISSION自动检测) + 持久会话上下文(Claude Code模式) + 结构化压缩归档(QwenPaw模式) + 工具结果裁剪(QwenPaw模式) + 6项性能优化 + 浏览器 v0.7.1

---

## 1. 项目概述

### 1.0 为什么是 MengPaw

中国的数字生态是被切碎的。

```
微信    → 能发消息，不能管文件
钉钉    → 能管审批，不能控设备
米家    → 能控小米设备，不认华为
小爱    → 能听懂指令，但不能跨 App 执行
各种 AI → 能聊天，但不能"活着"
```

每一片单拿出来都有用，但片与片之间没有连接。用户生活在一片数字群岛中——能力不缺，缺的是把能力连起来的**轮毂**。

两种路线：

| 路线 | 做法 | 结果 |
|------|------|------|
| **再造一个平台** | 自己写 LLM + 自己写文件管理 + 自己写设备控制 + 自己写消息系统 | 第 N+1 个碎片 |
| **微内核编排** | 不造轮子，造轮毂。用插件把已有的碎片桥接成一个整体 | 横切面 |

MengPaw 走第二条路。核心理念有两条：

> **Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

这是给 Agent 的行动自由。

> **用碎片打败碎片化。**

这是给 Agent 的存在意义——不是做一个更好的 App，是做 App 之间的那个东西。碎片化越严重，连接器本身越有价值。中国数字生态永远不会统一——微信不会和钉钉合并，小米不会和华为打通——但用户需要一个横跨所有碎片的 Agent 层。

```
应用层：微信 / 钉钉 / 米家 / 飞书 / WPS / ...     ← 碎片，不替代
Agent层：MengPaw 微内核 + 插件网格 + 记忆孪生    ← 轮毂，只做这个
设备层：手机 / 电脑 / 平板 / 车载 / ...            ← 节点，越多越强
```

### 1.1 架构定位

MengPaw（檬爪）— 微内核 + 插件架构的 Agent 框架。当前运行于 Android，架构设计上可移植到 Linux / Windows / macOS / 鸿蒙。

| 特征 | 说明 |
|------|------|
| 微内核 | `mengpaw-kernel` — 纯 Kotlin/JVM 模块，46 文件，零 Android 依赖，CLI/LLM/安全/会话/插件框架/Goal-Mission 模式全部可脱离 Android 测试 |
| 适配层 | `mengpaw-core` — 仅 6 个源文件，提供 Android 桥接（Vault 加密存储 / IntegrityGuard / SysExecutor）。移植到新平台只需重写这 6 个文件 |
| 插件同级 | 内置功能 (`sys`) 与外挂插件同等地位，均实现 `Plugin` 接口，均只依赖 kernel |
| 零 Python | 纯 Kotlin，无 Python 运行时 |
| 多通道 | AIDL（系统集成）/ Unix Socket（Termux）/ HTTP（调试） |
| 独立浏览器 | `mengpaw-browser` v0.7.1，Intent 互通，45 条浏览器操控命令 |
| 多模型 | 12 LLM Provider — OpenAI / DeepSeek / Kimi / GLM / Qwen / Grok / 火山引擎 / OpenModel / Self-Hosted / 自定义 |
| 插件市场 | GitHub Pages 托管 `plugins.json`，ETag 缓存，SHA256 校验 |
| 记忆孪生 | v0.15.0 — 跨设备 Agent 记忆同步 + 哈希链账本 + 短码配对 + 心跳保活 + QoS 自适应 + 手动 IP 发现 (plugin-memory-twin v0.2) |
| Agent 自我升级 | `plugin.marketplace` → `plugin.search` → `plugin.install` → 命令即可用 |
| 内置 Loop 模式 | Goal / Mission / Mission+ 三种模式直接内置在 AgentEngine，含 RubricGate 自动完成评估 |
| Agent 推送 | `notify.message` / `notify.banner` — Agent 主动向用户推送消息和横幅 |

### 1.2 成为贾维斯的三个维度

市面上的"贾维斯"本质是带 tool calling 的聊天框。真正的贾维斯有三个维度：

| 维度 | 含义 | MengPaw 现状 |
|------|------|-------------|
| **Continuity（连续性）** | Agent 不绑定一台设备，跨空间持续存在。回家问"今天涨了吗"，不需解释上下文 | ✅ 记忆孪生 + 身份文档同步 |
| **Ubiquity（无处不在）** | 同时存在于手机、电脑、服务器，自主判断哪个节点适合什么任务 | ✅ ACP + 能力路由 + 孪生发现 |
| **Agency（主动性）** | 不需要用户叫——自己判断什么需要做、什么需要汇报 | ⏳ Trigger 引擎 + NotifyBus 有雏形，守护态（哨兵模式）是下一步 |

> 局域网点对点是**特征**，不是妥协。数据不出局域网意味着隐私由物理定律保障而非信任承诺，每个节点独立存活意味着没有单点故障，零服务器费用意味着这是给普通人的东西。

适用场景：数字管家、自动化研究、RPA 替代、边缘 AI、设备网格、隐私第一的个人计算。

---

## 2. 架构设计

### 2.1 微内核分层 (v0.8.0)

```
┌──────────────────────────────────────────────────┐
│  mengpaw-shell (APK)     mengpaw-browser (APK)   │  ← UI 层
│  ├─ AgentRuntime  ← UI/运行时分离 (NEW)          │
│  ├─ AgentViewModel ← 轻量状态持有                 │
│  └─ Compose UI    ← 纯展示                       │
├──────────────────────────────────────────────────┤
│  mengpaw-core (6 文件, Android 适配)              │  ← 平台桥接
├──────────────────────────────────────────────────┤
│  mengpaw-kernel (46 文件, 纯 Kotlin/JVM)          │  ← 微内核
│  CLI · LLM · Session · Plugin · Security          │
│  AgentEngine · Goal/Mission · MCP · ACP           │
│  NotifyBus · Error · Trigger · Namespace          │
├──────────────────────────────────────────────────┤
│  plugins/ (25 模块, 同级, 均只依赖 kernel)         │  ← 插件层
└──────────────────────────────────────────────────┘
```

**关键设计决策 (v0.8.0 更新)**：
- **UI/运行时分离**: AgentRuntime 处理所有后台 IO 工作，UI 只观察 StateFlow。ViewModel 不含业务逻辑
- **QwenPaw 风格初始化**: 安装时创建 workspace 文件 → 用户配置 API → 用户发第一条消息才启动 Agent。无后台静默初始化
- Kernel 是纯 JVM 模块（`kotlin("jvm")`），可脱离 Android 在 JVM 上编译和测试
- 内置功能与外挂插件**同级**：`sys` 命名空间通过 `AgentEngine.additionalNamespaces` 注入
- `mengpaw-core` 仅含 Android 专有代码：Vault (Keystore 加密)、IntegrityGuard、StorageMonitor、SysExecutor、DataPathsInitializer、AndroidLogger

### 2.2 模块清单

| 模块 | 类型 | 源文件 | 版本 | 说明 |
|------|------|--------|------|------|
| mengpaw-kernel | JVM Library | 54 | 0.17.0 | 微内核：纯 Kotlin，零 Android 依赖 |
| mengpaw-core | Android Library | 6 | — | Android 适配层：Vault / IntegrityGuard / SysExecutor |
| mengpaw-design-system | Android Library | 5 | — | Arco 主题 / Markdown 渲染 / 基础组件 |
| mengpaw-shell | APK | 25 | 0.9.1 (vc=91) | 主应用：AgentRuntime + Chat UI + 设置 + 会话管理 (独立持久化/切换恢复/跨会话搜索) + 智能体管理 + 扩展功能重构 |
| mengpaw-browser | APK | 12 | 0.7.1 (vc=71) | 5标签预渲染 + 会话持久化 + 收藏夹 + App横幅屏蔽 + 平板标签栏白色主题 + 手机标签对话框 + 暗色模式 + file:// + WebView版本 + 27文件架构 |

### 2.3 内置命名空间（在 kernel 中，始终可用）

| 命名空间 | 源文件 | 命令数 | 职责 |
|---------|--------|--------|------|
| `self` | SelfExecutor.kt | 15 | Agent 自省 (status/config/stats/version/avatar/theme/mcp/trigger/acp/tools/search/search.stats/time/notify.message/notify.banner) |
| `agent` | AgentExecutor.kt | 35 | 文档(6) + 记忆三轨(14) + 其他(5) + 会话(4) + 工作区文件(6) |
| `plugin` | PluginExecutor + DevPlugin | 11 + 4 | 插件管理 (marketplace/search/install/uninstall/list/info/enable/disable/update/upgrade/auto + create/audit/share/examples) |
`framework` | FrameworkPlugin | 6 | 框架发现 (discover/peers/trust/untrust/info/ping) [↔ 同捆插件 plugin-framework] |

> `sys` 命名空间 (39 命令) 在 `mengpaw-core` 中实现；`framework` 由 `plugin-framework` 捆绑插件提供。均通过 `additionalNamespaces` 注入 AgentEngine，与其他插件同级。

### 2.4 依赖关系

```
mengpaw-shell
  ├── mengpaw-kernel (微内核)
  ├── mengpaw-core (Android 适配)
  ├── mengpaw-design-system (主题)
  └── 4 捆绑插件: memory / skill / framework / dev

mengpaw-browser
  ├── mengpaw-kernel
  ├── mengpaw-core
  └── mengpaw-design-system

plugins/ (23 模块)
  └── mengpaw-kernel  ← 所有插件只依赖微内核（同级）
```

### 2.5 响应式布局

基于 Material 3 `WindowSizeClass` + 自定义 `isWide()`（≥ Medium）：

| 宽度 | 左侧栏 | 右侧栏 | 设置页 | 聊天 |
|------|--------|--------|--------|------|
| Compact (<600dp) | 浮层叠加 | 浮层叠加 | 68dp 图标侧栏 | 全宽 |
| Medium+ (≥600dp) | 持久钉住 280dp | 持久钉住 300dp | 240dp 侧栏 + 内容区 | 自适应 |

- 平板模式下左右侧栏可独立钉住，内容区自动收缩
- 设置页 iPad 式双栏：侧栏 + 内容区，三大分区（Agent / 框架 / 系统）

### 2.6 跨 APK 通信

- Shell (com.mengpaw.shell) ↔ Browser (com.mengpaw.browser)，通过 Intent 互相唤醒
- 双方启动时检测对方安装状态，未安装时静默回退
- Shell 未装 Browser → 回退内置 WebView (BrowserScreen)
- Browser 未检测到 Shell → 隐藏唤醒按钮

### 2.7 数据流

用户输入 → LLM Core 生成 CLI 命令 → Pipeline（解析→安全→执行）→ 命名空间 → 结果返回 LLM → 循环至 Final Answer 或达上限

AgentEngine 支持四种执行模式：

| 模式 | 方法 | 说明 |
|------|------|------|
| **ReAct** | `run()` | Thought → Action → Observation 标准模式，含循环检测和最大步数限制 |
| **Plan-Execute** | `runWithPlan()` | LLM 分解任务为 3-7 步计划，逐步执行，每步独立 mini ReAct 循环 |
| **Goal** | `runWithGoal()` | 单目标驱动 + RubricGate 自动完成评估（参考 QwenPaw GoalMode） |
| **Mission** | `runWithMission()` | LLM 拆解子任务 → Worker 执行 → Verifier 验证（参考 QwenPaw MissionMode） |

**Goal 模式架构**:
```
runWithGoal(task, maxTurns, maxTokens)
  ├── GoalSession — 目标状态 (goal/active/iteration/tokensUsed/verdict)
  ├── GoalTurnGate — 迭代计数 + 上限检查
  ├── GoalBudgetGate — token 预算检查
  └── RubricGate — LLM 评估 "目标完成了吗?" → YES=结束 / NO=继续
```

**Mission 模式架构**:
```
runWithMission(task, maxSubtasks, maxStepsPerSubtask)
  ├── Phase 1: LLM 拆解 → List<MissionSubtask>
  ├── Phase 2: 每个子任务 → run() ReAct 独立执行
  ├── Phase 3: Verifier 验证每个子任务结果
  └── Phase 4: 最终报告 (verified/failed 统计)
```

**运行时 Provider 更新**: `updateLlmProvider()` 允许在 Agent 运行中切换 LLM Provider，配合设置页 Per-Agent 模型选择。

### 2.8 跨平台可移植性策略

微内核分层的核心价值：**kernel 是纯 Kotlin/JVM，46 个文件零 Android 依赖。** 移植到新平台只需重写 `mengpaw-core` 的 6 个适配文件。

#### 平台评估矩阵

```
                    kernel   core    sys命令   插件    UI      后台   整体
                    ──────   ────   ──────   ────   ────    ────   ────
Linux/Win/macOS     🟢 零改  🟢 6文件 🟢 更强 🟢 全复用 🟢 CMP 🟢 无限制 🟢🟢🟢
鸿蒙                🟢 可用  🟡 适配 🟡 逐个 🟡 机制改 🔴 重写 🟡 模型改 🟡🟡
iOS                 🟢 编译  🟡 可行 🔴 <10个 🔴 无动态 🔴 全重写 🔴 阉割  🔴
```

| 平台 | 优势 | 障碍 | 可行性 |
|------|------|------|--------|
| **桌面端 (Linux/Win/Mac)** | kernel 零改动，23 插件全复用；桌面端 sys 命令比 Android 更强（无权限限制）；Compose Multiplatform 成熟 | 需写一个 6 文件的 `mengpaw-desktop` 适配层 | 2-3 周可达 MVP，是下一步最自然的方向 |
| **鸿蒙** | kernel 可用；鸿蒙分布式设备管理是 Android 米家 App 的超集——同一个 IoT 控制需求在鸿蒙上更干净；同一个能力在不同平台只是碎片形态不同 | UI 需 ArkUI 全部重写；分发模型不同（AppGallery，不能 sideload APK）；碎片生态还在生长 | 技术可行但等待碎片成熟更重要 |
| **iOS** | kernel 能编译（Kotlin/Native + ktor Darwin engine） | ProcessBuilder 不可用（CLI 执行是 Agent 核心循环）；文件系统隔离（fs.* 无意义）；动态代码加载禁止（插件系统废掉）；后台限制极严 | 能编译≠产品有意义。这是哲学问题，不是技术问题 |

#### 核心原则

> **不在一个平台上替代其生态，而是在每个平台上桥接其已有的碎片。**
>
> 碎片不只是应用。同一个用户需求在不同平台有不同的碎片形态。`plugin-iot` 在 Android 上调米家 API，在鸿蒙上调分布式设备 API——给 Agent 的命令始终是 `iot.control <device> <action>`，Agent 不关心底层是谁。**微内核的可移植性保证追着碎片走的能力，而不用被绑死在某个平台上等待碎片长大。**

#### 设备网格：极轻接入

每个设备不一定是 Android 手机——一块 ESP32、一个码表、一台实验室仪器，只要能发 HTTP，就能成为 MengPaw 网格的一个节点：

```
设备种类                    接入方式              代价
─────────────────────    ───────────────     ──────────
Android 手机             MCP over ACP        零代价，已在网格
鸿蒙设备                  MCP over ACP        HTTP + JSON-RPC
码表（自定义固件）         MCP over HTTP       30 行 endpoint
体重秤（蓝牙→手机桥接）   插件桥接              手机当网关
ESP32 传感器              MCP over WiFi       几十行 C
手环（厂商 Health API）  插件桥接              手机当网关
```

MCP JSON-RPC 是通用语言，ACP P2P 是加密通道，孪生是共享记忆。任何能讲 MCP 的设备都能被 Agent 发现和调用——不需要 Gradle 模块，不需要 Android 权限，不需要 UI。

---

## 3. 模块详解

### 3.1 mengpaw-kernel（微内核，46 文件）

| 包 | 文件数 | 关键类 |
|----|--------|--------|
| `cli/` | 6 | CliInterpreter, CommandRegistry, CommandExecutor, Pipeline, CommandSearch (BM25), CliAudit |
| `llm/` | 6 | AdaptiveLlmProvider, LlmProvider, LlmRequestBuilder, PromptEngine, RemoteApi, TranslateMiddleware |
| `session/` | 3 | SessionManager, History, Checkpoint |
| `plugin/` | 4 | Plugin, PluginManager, PluginExecutor, PluginMarketplaceClient |
| `agent/` | 9 | AgentDocManager, AgentDocs, AgentExecutor, AgentMiddleware, AgentProfile, DreamEngine, PromptBuilder, ScrollContext, GoalSession |
| `security/` | 4 | Sanitizer, SecurityPolicy, PromptFirewall, IntegrityProvider |
| `mcp/` | 2 | McpServer, McpClient |
| `acp/` | 7 | AcpProtocol, AcpServer, AcpCrypto, AcpTransport, DelegateHandler, McpOverAcpBridge, ShareMemoryHandler |
| `mission/` | 1 | MissionMonitor |
| `error/` | 1 | ErrorCollector |
| `extension/` | 1 | ManifestParser |
| `trigger/` | 1 | TriggerEngine |
| `namespace/` | 3 | SelfExecutor, ScreenshotManager, NotifyBus |
| 根 | 4 | AgentEngine, DataPaths, KernelLog, KernelDispatchers |

> **v0.17.0 新增**: `AgentEngine` 持久会话 (Claude Code 模式) — 多次 `run()` 复用同一 Session，LLM 看到完整对话历史；QwenPaw 风格结构化压缩 (Goal/Progress/KeyDecisions/NextSteps/CriticalContext) + 对话归档 (`dialog/YYYY-MM-DD.jsonl`) 保证零数据丢失；工具结果双阈值裁剪 (≤3步 30KB / 更早 2KB) + 文件外存。`SessionManager` 结构化摘要 + `agentName` 绑定。`DataPaths` 新增 `dialogArchiveDir` / `toolResultsDir`。
> **v0.6.1 新增**: `GoalSession.kt` (GoalSession + RubricEvaluator + MissionSubtask), `NotifyBus.kt` (Agent→User 推送总线), SelfExecutor +5 命令

### 3.2 mengpaw-core（Android 适配层，6 文件）

| 文件 | 职责 |
|------|------|
| `security/Vault.kt` | API Key 加密存储 (EncryptedSharedPreferences + Android Keystore) |
| `security/IntegrityGuard.kt` | APK 签名校验，实现 `IntegrityProvider` 接口 |
| `security/StorageMonitor.kt` | 磁盘空间监控 (android.os.StatFs) |
| `namespace/SysExecutor.kt` | 系统信息命令 (39 个，反射 Android API) |
| `DataPathsInitializer.kt` | 桥接：`DataPaths.initialize(context.filesDir)` |
| `AndroidLogger.kt` | 桥接：`KernelLog.setLogger(AndroidLogger())` |

### 3.3 mengpaw-shell（主应用，24 文件，v0.8.4）

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 入口 + 初始化 + 启动恢复配置 + 退出设置时 applyConfiguration |
| `service/AgentRuntime.kt` | **NEW** UI/运行时分离 — 触发器桥接, 所有 IO 工作在此 |
| `ui/screens/` (14 文件) | MainScreen, AgentViewModel, PluginViewModel, PluginMarketScreen, PluginDetailScreen, SettingsScreen, SettingsViewModel, BrowserScreen, HistorySidebar, SidebarContent, SplashScreen |
| `ui/components/` (5 文件) | BigBangPopup, MissionMonitorOverlay, TokenChart, TokenStatsCollector, NotifyBanner |
| `ui/AdaptiveLayout.kt` | WindowSizeClass 计算 |
| `ui/localization/Strings.kt` | 中英双语注解 |
| `service/` (4 文件) | ShellService, DreamWorker, EventReceiver, WakeReceiver |

**v0.8.0 核心变更**:
- **AgentRuntime**: 所有 Agent 初始化(文件 I/O + Provider 创建 + LLM 调用)在 IO 线程, UI 只观察 StateFlow
- **QwenPaw 初始化**: `安装→配置→用户发消息` 三阶段, 无静默自动启动
- **会话持久化**: 30s 自动保存 + 退出保存 + 启动恢复, 思考链完整存储
- **智能体管理**: 点击切换 / 长按名片 / 删除确认 / 添加框架
- **输入优化**: Enter 发送 / Shift+Enter 换行 / 发送后聚焦

**v0.10.0 核心变更**:
- **框架协议插件**: mDNS 局域网注册/发现, framework.* 6 命令, 持续扫描, 指纹/Agent列表广播
- **框架名片**: 长按查看名称/版本/地址/Agent列表/备注/信任, 可编辑
- **亮/暗/跟随系统**: 三档主题切换, 跟随系统暗色模式
- **扩展功能重构**: 文件提交区 (图片/文档/文件/拍照) + 执行模式区 (/Mission /Research /Translate /Dream) + 插件工具区
- **输入标签系统**: AssistChip 标签显示活跃模式, @agent 自动补全
- **侧边栏交互**: 顶栏头像替代菜单图标, 全局右滑/左滑
- **PAD悬浮窗移除**: 物理删除 plugin-pad 目录及所有引用
- **Gemini 路由**: ip-api.com → 系统时区/语言判断, 默认走 Gitee

**v0.11.0 核心变更**:
- **Markdown 引擎重构**: commonmark-java AST 替代手写 ~300 行正则解析器, GFM 表格/删除线/嵌套列表
- **线程优化**: MarkdownText Column+verticalScroll, AgentDocs→Dispatchers.IO
- **视觉表格渲染**: cells<80→Compose 行列布局, ≥80→等宽文本

**v0.11.3 核心变更**:
- **嵌套滚动根除**: MarkdownText 新增 nestedScroll 参数, License/Attribution 不再闪退
- **ShellService 崩溃修复**: deleteNotificationChannel SecurityException
- **许可证/致谢独立页面**: 全文 res/raw 嵌入, Markdown 渲染
- **ATTRIBUTIONS 更新**: 添加 commonmark-java 和 MarkLeaf 致谢

**v0.9.1 核心变更**:
- **品牌焕新**: 主题色 #165DFF→#0E4397 (深蓝)，辅助色 →#FC5185 (粉色)，ArcoColors 蓝色系/Pink 系列全面更新
- **启动页**: 代码绘制 "WOW BLUE" 替换为品牌 "哇" 矢量图标 (ic_wowblue_icon.xml) + "WowBlue" 文字
- **扩展功能重构**: 文件提交区 (图片/文档/文件/拍照，利用 Android 文件选择器) + 执行模式区 (/Mission /Research /Translate /Dream) + 插件工具区
- **输入标签系统**: AssistChip 标签显示活跃模式，× 清除，持久保留
- **@agent 自动补全**: 输入 @ 弹出已创建 Agent 列表，替换文本 + 添加标签
- **气泡标注**: Agent 回答头部显示 · /Mission · N 步 或 · @agent 等标注

**v0.8.4 核心变更**:
- **会话管理增强**: 独立会话文件 (`sessions/{id}.json`) + `switchToSession()` 切换恢复 + `agent.sessions` 跨会话搜索 + 原子写入防损坏
- **引擎可靠性**: 安全命令白名单 (19 个) 防循环误判 + 引擎状态重置防跨任务污染 + 异常时全面状态同步
- **UI 升级**: 消息区自适应宽度 (平板 80%/手机 95%) + 思考完成自动定位 + 侧栏真实头像 + 框架通讯录持久化
- **Markdown 增强**: 新增 Heading 块 + Agent 消息非等宽字体

### 3.4 mengpaw-browser（独立浏览器，30+ 文件）

| 目录/文件 | 职责 |
|-----------|------|
| `BrowserActivity.kt` | 薄 Activity — 生命周期、MCP、返回键、onTrimMemory |
| `data/` (3 文件) | BrowserTypes, BrowserPrefs (含书签+会话持久化), HistoryStore |
| `service/GoogleTranslate.kt` | 免费翻译客户端 |
| `web/WebViewFactory.kt` | WebView 工厂 + App横幅CSS屏蔽 + onReceivedError |
| `util/` (3 文件) | AdBlocker, SmartNavigate, DownloadUtil |
| `ui/` (12 文件) | 9 弹窗 + FindBar + ReaderMode + Icons |
| `ui/components/` (2 文件) | TabChip (标签样式), SearchEngineLogo (SVG) |
| `ui/theme/BrowserThemeConfig.kt` | Agent 主题配置 |
| `bridge/BrowserBridge.kt` | Java↔JS 双向桥 |
| `plugin/` (3 文件) | BuiltinBrowserPlugin, BrowserPlugin, BrowserPluginRegistry |

**v0.7.0 新特性**: 5标签预渲染(alpha可见性切换) + 会话持久化(杀进程恢复) + 收藏夹 +
App横幅CSS屏蔽(30+选择器) + 平板标签栏白色主题 + 暗色模式自适应 + onTrimMemory内存保护

### 3.5 插件模块（23 个，plugins/ 目录）

#### 基础功能 (8)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-fs | fs | cat, ls, write, rm, mkdir, cp, mv, stat, grep, glob (10) | |
| plugin-net | net | curl, get, post (3) | |
| plugin-memory | memory | ls, read, write, rm, search, stats (6) | ⭐ |
| plugin-skill | skill | ls, run, enable, disable (4) | ⭐ |
| plugin-clipboard | clipboard | copy, paste, clear (3) | |
| plugin-notification | notification | send, list, dismiss (3) | |
| plugin-self | self | status, config, stats, version (4) | |
| plugin-framework | framework | discover, peers, trust, untrust, info, ping (6) | ⭐ |

#### AI / 搜索 (4)

| 模块 | 命名空间 | 命令 |
|------|---------|------|
| plugin-tavily | tavily | search, extract (2) |
| plugin-render | render | models, generate, status, preview (4) |
| plugin-comfy | comfy | nodes, workflow, run, preview, export (5) |
| plugin-translate | translate | text, auto, langs, setup (4) |

#### 多智能体 (3)

| 模块 | 命名空间 | 命令 |
|------|---------|------|
| plugin-hermes | hermes | team, discover, delegate, ask, memo, role (6) |
| plugin-workflow | workflow | run, define, list, status (4) |
| plugin-incubator | incubator | spawn, list, terminate, inbox (4) |

#### Agent 运行模式 (内置)

> Goal / Mission / Mission+ 三种 Loop 模式已内置在 AgentEngine 中，不再作为独立插件。

| 模式 | 引擎方法 | 核心机制 |
|------|---------|---------|
| **Goal** | `AgentEngine.runWithGoal()` | GoalSession + 三层 Gate (GoalTurnGate/GoalBudgetGate/RubricGate) — LLM 自动评估完成度 |
| **Mission** | `AgentEngine.runWithMission()` | LLM 拆解子任务 → Worker 独立 ReAct 执行 → Verifier 验证 |
| **Mission+** | `runWithMission()` + ACP | Mission 模式 + 跨 ACP 框架/设备协调 |

#### 浏览器扩展 (5)

| 模块 | 命名空间 | 命令 |
|------|---------|------|
| plugin-browser-push | browser.push | push, push.pending, push.accept, push.reject (4) |
| plugin-browser-search | search | extract, summary, engines (3) |
| plugin-browser-mcp | browser.mcp | tools, status (2) |
| plugin-browser-cdp | cdp | enable, status (2) |
| plugin-browser-inspector | inspector | start, stop, select, inspect (4) |

#### 工具链 (3)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-dev | plugin | create, audit, share, examples (4) | ⭐ |
| plugin-error-report | error | list, show, clear, export, status, upload (6) | |
| plugin-update | update | check, download, install, auto (4) | |

> ⭐ = 捆绑在 Shell APK 中，随主应用安装，无需手动下载

### 3.5.1 记忆孪生架构 (plugin-memory-twin v0.2)

11 文件, ~3000 行。基于 ACP 协议 + 哈希链账本 + 短码配对 + 心跳保活 + QoS 自适应。

#### 组件

| 文件 | 职责 |
|------|------|
| `MemoryTwinPlugin.kt` | 插件入口, 24 条 `twin.*` CLI 命令注册 |
| `TwinLedger.kt` / `TwinLedgerStore.kt` | SHA-256 哈希链账本数据模型和持久化 (JSON Lines) |
| `TwinSyncEngine.kt` | 同步状态机 (HEAD→PULL→BATCH→ACK) + 心跳保活 + QoS 自适应 |
| `TwinAcpHandler.kt` | `AcpHandler` 实现 — 处理 9 种孪生 ACP 消息类型 |
| `TwinDiscovery.kt` | Android NSD (mDNS) 局域网自动发现 |
| `TwinPairingEngine.kt` | 短码验证配对协议 (4 步: ANNOUNCE→CHALLENGE→VERIFY→CONFIRM) |
| `TwinCapability.kt` | `CapabilityCard` + `TwinCapabilityCollector` — 设备能力采集与协议版本协商 |
| `TwinRouter.kt` | 能力感知任务路由 |
| `TwinIdentity.kt` | soul/profile 身份文档同步 |
| `TwinDreamSync.kt` | 梦境事件账本集成 |

#### 配对流程 (UI 隐藏, 5 连击触发)

```
侧边栏 MengPaw 框架图标 → 5 连击
  → 确认弹窗 (显示目标设备名)
  → 激活孪生 (如未激活) + 轮询等待 ACP 就绪 (最多 5s)
  → TwinPairingEngine.initiatePairing() → CAPABILITY_ANNOUNCE + nonce
  → 双方各自显示 6 位验证码 → 用户比对
  → PAIR_CONFIRM → 派生 AES-256 密钥 → 信任持久化
  → 自动注入配对指引到工作区 inbox
```

#### 同步协议

```
设备 A                          设备 B
  │  LEDGER_HEAD ──────────────→ │  交换账本头部
  │ ←────────────── LEDGER_HEAD │
  │  LEDGER_PULL ──────────────→ │  请求缺失条目
  │ ←──────────── LEDGER_BATCH  │  批量返回 (含跨链验证)
  │  LEDGER_ACK ───────────────→ │  确认收妥
  │                               │
  │  [每 30s 双向 HEARTBEAT]      │  心跳保活, 90s 无响应→离线
```

#### QoS 自适应

| 网络类型 | 同步间隔 | 内容 |
|---------|:--:|------|
| WiFi / Ethernet | 60s | 全量: 账本 + 身份 + 梦境 |
| 移动网络 (非计费) | 300s | 仅 MEMORY 类型条目 |
| 按流量计费 | 暂停 | 仅 `twin.sync` 手动触发 |

#### 发现机制 (双通道)

| 通道 | 方式 | 适用场景 |
|------|------|---------|
| mDNS 自动发现 | `TwinDiscovery` — `_mengpaw-twin._tcp` | 同 WiFi, 多播可达 |
| 手动 IP 添加 | `twin.peer.add <ip> [port] [name]` | 多播隔离, 跨网段, 不同频段 |

#### 核心 CLI 命令 (24 条)

```bash
# 生命周期
twin.start / twin.stop / twin.status

# 节点管理
twin.peers / twin.peer.info <id> / twin.peer.add <ip> [port] [name]

# 配对 (CLI 不可执行, 引导至 5 连击)
twin.pair / twin.unpair

# 同步
twin.sync [peer] / twin.sync.auto on|off / twin.sync.qos wifi|mobile|metered

# 能力与路由
twin.capabilities [--self|--all|<peer>] / twin.delegate <peer> <task> / twin.route <task>

# 账本
twin.ledger.show [limit] / twin.ledger.verify / twin.ledger.diff <peer> / twin.ledger.stats

# 身份文档
twin.identity.push / twin.identity.pull [peer] / twin.identity.diff <peer> / twin.identity.merge <peer>

# 梦境
twin.dream.sync / twin.dream.history [limit]
```

#### 系统提示词集成

中英文系统提示词含完整的记忆孪生使用指南 (11 项): 功能概述 / 状态检查 / 节点发现 / 手动同步 / 任务委派 / 能力对比 / 路由推荐 / 账本审计 / 配对方式 / 启动前提 / 解绑方式。

### 3.6 构建配置

| 配置 | Shell | Browser | Core | Kernel |
|------|-------|---------|------|--------|
| 插件类型 | com.android.application | com.android.application | com.android.library | kotlin("jvm") |
| compileSdk | 35 | 35 | 35 | — |
| minSdk | 26 | 26 | 26 | — |
| targetSdk | 35 | 35 | — | — |
| versionName | 0.11.3 | 0.6.0 | — | 0.11.3 |
| versionCode | 1130 | 8 | — | — |
| R8 | Release 启用 | Release 启用 | 关闭(库模块) | — |

**Shell 权限** (17 项):
- 网络: INTERNET, ACCESS_NETWORK_STATE
- 保活: FOREGROUND_SERVICE, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK
- 悬浮窗: SYSTEM_ALERT_WINDOW
- 内置工具: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, CAMERA, QUERY_ALL_PACKAGES
- 插件: REQUEST_INSTALL_PACKAGES
- 文件/媒体: READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE
- 未来扩展: RECORD_AUDIO, VIBRATE

**Browser 权限**: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS (Android 13+)

### 3.7 测试 (9 文件，全部在 mengpaw-kernel)

| 测试文件 | 内容 |
|----------|------|
| AgentEngineTest | PlanStep/TaskPlan/ReAct 循环/状态转换 |
| AdaptiveLlmProviderTest | Provider 检测/配置/Fallback/异常 |
| SecurityTest | 脱敏/策略/拦截 |
| CliInterpreterTest | 分词/引号/转义/flags |
| ManifestParserTest | 版本/兼容/加载 |
| SessionManagerTest | 会话 CRUD/追踪 |
| PipelineTest | 执行/安全/文件读写 |
| PromptEngineTest | ReAct/中英文/循环检测 |
| CommandRegistryTest | 注册/命名空间/列表 |

> Kernel 测试在 JVM 上运行（`./gradlew :mengpaw-kernel:test`），毫秒级反馈，无需模拟器。

---

## 4. 核心概念

### 4.1 LLM 调用链

`AdaptiveLlmProvider` 提供统一的 LLM 调用接口：
- **Provider 自动检测**: 根据 endpoint URL 识别（openai/deepseek/kimi/glm/qwen/grok/volcano/openmodel/selfhosted）
- **认证适配**: GLM 使用裸 API Key，其他使用 Bearer Token
- **指数退避重试**: 默认 2 次重试，延迟 500ms×(1,2,4...)
- **Fallback 降级链**: primary → fallback[0] → fallback[1] → ... → `LlmFallbackExhaustedException`
- **响应格式归一化**: 兼容 OpenAI `choices[0].message.content` 和 GLM `data[0].content`

### 4.2 支持的服务商 (12)

| 服务商 | Endpoint | 默认模型 | 缓存策略 |
|--------|----------|----------|----------|
| OpenAI | api.openai.com | gpt-4o | CACHE_CONTROL |
| DeepSeek | api.deepseek.com | deepseek-chat | PREFIX_STABLE |
| Kimi | api.moonshot.cn | moonshot-v1-8k | CACHE_CONTROL |
| GLM | open.bigmodel.cn | glm-4-plus | CACHE_CONTROL |
| Qwen | dashscope.aliyuncs.com | qwen-plus | CACHE_CONTROL |
| Grok | api.x.ai | grok-2 | PREFIX_STABLE |
| 火山引擎 | ark.cn-beijing.volces.com | 豆包 | CACHE_CONTROL |
| OpenModel | 自定义 | 自定义 | PREFIX_STABLE |
| Self-Hosted | 自定义 | 自定义 | CACHE_CONTROL |
| Custom | 自定义 | 自定义 | CACHE_CONTROL |

### 4.3 对话压缩

`SessionManager.compressIfNeeded()` — 消息数超过 50 条时，调用 LLM 将旧消息压缩为 system summary，保留最近 10 条完整上下文，上限 200 条。

### 4.4 翻译中间件

美国模型 (OpenAI/Grok) 自动中→英→模型→英→中流水线，为中文用户节省约 40% token 消耗。

### 4.5 Agent→User 主动推送 (NotifyBus)

Agent 可通过 CLI 命令主动向用户推送消息和横幅，无需等待用户输入。

**架构**:
```
Agent CLI → SelfExecutor.notifyMessage/notifyBanner
         → NotifyBus (SharedFlow)
         → UI 层观察 → 消息注入聊天 / 横幅覆盖层渲染
```

**CLI 命令**:
- `notify.message <text>` — 将消息注入聊天列表 (System 角色)
- `notify.banner <text> [--level info|success|warn|error]` — 显示顶部横幅，4 秒自动消失

**Usecase**: 长任务完成通知、异常告警、阶段性进度汇报、无需用户追问。

### 4.6 被动索引系统

Agent 通过 memory 命令按需加载文档：
- `memory read cli-reference` — CLI 完整参考
- `memory read tool-index` — 命令快速索引
- `skill.ls` + `skill.run <name>` — 先索引再加载具体 Skill

### 4.7 MCP 协议：通用设备语言

MCP 在 MengPaw 中的定位不是"让 AI 调用工具的协议"，而是**让任何碎片设备加入 Agent 网格的通用语言**。

#### 协议的本质

MCP 协议极其简单——JSON-RPC + 三个原语（tool / resource / prompt）。好的协议都是极简的，HTTP 几个动词统治了互联网三十年。MCP 的三个原语足够让任何设备在 Agent 网格中自描述和互操作。

#### 供应链安全

MCP 规范已公开发布，本身是一个描述性的文档规范，不是一个二进制的闭源服务。一旦发布，任何国家都无法"封锁"一段文本。MengPaw 将 MCP 作为**一个可替换的桥接插件**而非核心协议：

```
MengPaw 核心                         外部协议
──────────                         ──────────
Plugin 接口 ← 稳定标准             MCP ← 一个可替换的桥接插件
ACP 协议 ← 核心协议               MCP over ACP ← 一种桥接方式
CLI 命名空间 ← Agent 的通用语言    未来可用其他协议填同一位置
```

**MengPaw 的护城河是 Plugin 接口 + ACP 协议 + CLI 命名空间。MCP 只是众多插件可以桥接的外部协议之一。** 如果 MCP 出现不可用的情况，Agent 仍可通过 `net.curl` 裸调、通过自定协议插件桥接、通过文件式通信互通——核心完全不受影响。

#### 为什么很多人不理解 MCP

多数人只看到"让 ChatGPT 调用 GitHub API"，看不到"让体重秤、码表、ESP32 传感器用同一种语言被 Agent 发现和对话"。前者是 demo，后者是 Agent 的感官系统。做聊天框的人不需要理解设备联邦。

### 4.8 记忆三轨制 (v0.15.0+)

MengPaw 使用三层记忆架构。会话不是记忆形式——会话中的细节保留在按日分片的中期记忆中，由梦境模式按日压缩提炼。

```
长期记忆 (memory/memory.md)
  ← 注入系统提示词, Agent 每次对话可见
  ← 仅三种来源: 用户说「记住」/ Agent 自主判断重要 / 梦境整理产出
  ← 永远精简, 防提示词膨胀降智

中期记忆 (memory/memory_{date}.md)
  ← 按日期分片, 每日独立文件
  ← 自动记录: agent.memory.record, 对话摘要/事实/观察
  ← 不注入提示词, Agent 需要时主动查阅 (agent.memory.mid)
  ← 梦境模式按日分析中期记忆, 提炼洞察写入长期记忆

项目记忆 (memory/project_{name}_memory.md)
  ← 按项目名分片, 里程碑或闭环时总结
  ← 存储可复用的项目完成模式和方法论
  ← agent.memory.project 查看, agent.memory.project.save 写入
```

**数据流**:

```
会话细节 → agent.memory.record → 中期记忆 (按日分片)
                                       ↓
                              agent.dream (梦境整理, 按日压缩)
                                       ↓
                              提炼洞察 → agent.memory.keep → 长期记忆
                                       ↓
                              项目闭环 → agent.memory.project.save → 项目记忆
```

**命令族**:

| 层级 | 查看 | 写入 | 删除条目 | 编辑条目 | 删除文件 |
|------|------|------|---------|---------|---------|
| 长期 | `agent.memory` | `agent.memory.keep` | `agent.memory.rm` | `agent.memory.edit` | — |
| 中期 | `agent.memory.mid` | `agent.memory.record` | `agent.memory.mid.rm` | `agent.memory.mid.edit` | `agent.memory.mid.delete` |
| 项目 | `agent.memory.project` | `agent.memory.project.save` | `agent.memory.project.rm` | `agent.memory.project.edit` | `agent.memory.project.delete` |

**设计原则**:
- 系统提示词只注入长期记忆——防止上下文膨胀导致 Agent 降智
- 中期记忆可频繁读写, 旧文件可归档清理——不计入提示词 token 消耗
- 项目记忆是"可复用模式库"——不是日志, 是提炼过的方法论
- 所有写入使用原子操作 (tmp → rename), 防崩溃损坏
- 梦境模式 (`agent.dream`) 桥接中期→长期: 分析今日中期记忆, 产出结构化洞察

---

## 5. CLI 规范

### 5.1 内置命名空间（kernel）

#### self — Agent 自省 (13)
`status` | `config [key=value]` | `stats` | `version` | `avatar` | `theme` | `mcp` | `trigger` | `acp` | `tools [namespace]` | `time [format]` | `notify.message <text>` | `notify.banner <text> [--level]`

> **v0.6.1 新增**: `tools` — 按命名空间列出所有可用命令；`time` — 获取当前时间 (支持 iso/date/time/timestamp)；`notify.message` — Agent 推送消息到聊天；`notify.banner` — Agent 推送横幅 (支持 info/success/warn/error)

#### agent — 文档 + 内存 + 工作区 (27+)
**文档 (3)**：`docs` | `cli` | `profile` | `soul` | `boost` | `boost.delete`

**记忆三轨 (13)**：`memory` (看长期) | `memory.keep <内容>` (写长期) | `memory.rm <时间戳>` | `memory.edit <时间戳> <内容>` | `memory.mid [日期]` (看中期) | `memory.record <内容>` (写中期) | `memory.mid.delete <日期>` | `memory.mid.rm <日期> <时间戳>` | `memory.mid.edit <日期> <时间戳> <内容>` | `memory.project [名称]` (看项目) | `memory.project.save <名称> <内容>` | `memory.project.rm <名称> <时间戳>` | `memory.project.edit <名称> <时间戳> <内容>` | `memory.project.delete <名称>`

**其他 (5+)**：`audit` | `browser-tools` | `dream` | `cleanup` | `storage`

**会话 (4)**：`sessions [keyword] [limit]` | `session.delete <id>` | `session.archive <id>` | `session.current`

**工作区文件 (6)**：`read <path>` | `write <path> <content>` | `ls [path]` | `rm <path>` | `mkdir <path>` | `output`

> **v0.15.0 新增**: 三轨记忆完整命令族 (memory.keep/memory.mid/memory.project 及增删改查) | **v0.9.1 新增**: boost 创作加速器 | **v0.8.4 新增**: `sessions` 跨会话搜索

#### plugin — 插件管理 (11 + 4)
**内核 (11)**：`marketplace [--refresh]` | `search <query>` | `install <id>` | `uninstall <id>` | `list` | `info <id>` | `enable <id>` | `disable <id>` | `update <id>` | `upgrade --all` | `auto <wake\|sleep\|status\|sleep-idle>`

**dev 插件扩展 (4)**：`create --type script|jar --name <name>` | `audit <id>` | `share <id> --to <target>` | `examples`
> dev 插件的命令实际注册为 `dev.plugin.create` / `dev.plugin.audit` / `dev.plugin.share` / `dev.plugin.examples`，因为 PluginManager 根据插件 ID (`dev-plugin`) 自动派生命名空间 `dev`。

#### sys — Android 系统 (39 命令，通过 Android 适配层注入)

**设备信息 (1)**: `device` (型号/厂商/SDK/架构)

**电源 (4)**: `battery` | `power` | `power.save` | `screen.on`

**网络 (4)**: `network` | `wifi` | `wifi.enable` | `bluetooth`

**定位 (1)**: `location` (需权限)

**硬件 (4)**: `cpu` | `memory` | `storage` | `sensors`

**屏幕 (3)**: `display` | `screen.brightness <0-255>` | `screen.off`

**音量 (2)**: `volume` | `volume.set <type> <level>`

**相机 (1)**: `camera` (需权限)

**应用 (4)**: `apps` (需权限) | `app.launch <pkg>` | `app.uninstall <pkg>` | `app.info <pkg>`

**剪贴板 (2)**: `clipboard` | `clipboard.set <text>`

**Intent (3)**: `intent.open <url\|pkg>` | `intent.share <text>` | `intent.view <file>`

**通知 (3)**: `notification.id` | `notification.send <title> <text>` | `notification.cancel <id>`

**权限 (3)**: `permission.list` | `permission.request <name>` | `permission.check <name>`

**其他 (4)**: `telephony` | `vibrate [ms]` | `ringtone.play` | `alarm.set <seconds> <msg>`

### 5.2 插件命名空间

格式 `namespace.command arg1 arg2 "arg with spaces" --flag value`

#### fs — 文件系统 (10)
`cat <path>` | `ls [path]` | `write <path> <content>` | `rm <path>` | `mkdir <path>` | `cp <src> <dst>` | `mv <src> <dst>` | `stat <path>` | `grep <pattern> [path] [--regex] [-i] [--context N]` | `glob <pattern> [path]`

> **v0.6.1 新增**: `grep` — 按文本/正则搜索文件内容 (含上下文)；`glob` — 文件通配符模式匹配。参考 QwenPaw grep_search / glob_search 移植。

#### net — 网络 (3)
`curl <url>` | `get <url>` | `post <url> <body>`

#### memory — 记忆 (6)
`ls` | `read <id>` | `write <id> <content>` | `rm <id>` | `search <query>` | `stats`

#### skill — 技能 (7)
`ls` | `run <name>` | `enable <name>` | `disable <name>` | `info <name>` | `search <query>` | `create <name> <content>`

> v0.6.1: 内置 4 个默认 Skills (make-skill / make-plan / guidance / source-index)，参考 QwenPaw 移植。首次运行自动播种，已有 skill 时跳过。

#### clipboard — 剪贴板 (3)
`copy <text>` | `paste` | `clear`

#### notification — 通知 (3)
`send --title "T" --content "C"` | `list` | `dismiss <id|--all>`

#### tavily — AI 搜索 (2)
`search <query>` | `extract <url>`

#### hermes — 多智能体 (6)
`team` | `discover` | `delegate <agent> <task>` | `ask <agent> <question>` | `memo <content>` | `role <agent> <role>`

#### workflow — 工作流 (4)
`run <id>` | `define <json>` | `list` | `status <id>`

#### incubator — 孵化器 (4)
`spawn <config>` | `list` | `terminate <id>` | `inbox`

#### render — 图像生成 (4)
`models` | `generate <prompt>` | `status <job-id>` | `preview <job-id>`

#### comfy — ComfyUI (5)
`nodes` | `workflow <json>` | `run` | `preview` | `export`

#### translate — 翻译 (4)
`text <content>` | `auto <content>` | `langs` | `setup`

#### error — 错误上报 (6)
`list` | `show <id>` | `clear` | `export` | `status` | `upload`

#### update — 自动更新 (4)
`check` | `download` | `install` | `auto`

#### browser.push — 跨设备推送 (4)
`push <url>` | `pending` | `accept <id>` | `reject <id>`

#### search — 搜索分析 (3)
`extract <url>` | `summary <url>` | `engines`

#### browser.mcp — 浏览器 MCP (2)
`tools` | `status`

#### cdp — Chrome DevTools (2)
`enable` | `status`

#### inspector — 元素检查器 (6)
`start` | `stop` | `select <selector>` | `annotate <selector> <text>` | `list` | `export`
> `inspect` 命令 (旧文档) 已重命名为 `annotate`。

### 5.3 浏览器内置命令 (browser.*, 45)

**标签页 (5)**: `tabs` | `tab <N>` | `tab.open <N> <url>` | `tab.close <N>` | `tab.all`

**效率 (6)**: `nav <url>` | `batch <cmd1;;cmd2>` | `q <shorthand>` | `inject` | `diff` | `preload`

**页面操控 (6)**: `eval <js>` | `click <sel>` | `type <sel> <text>` | `scroll <x> <y>` | `content` | `screenshot`

**导航 (5)**: `open <url>` | `back` | `forward` | `title` | `url`

---

## 6. 安全模型

### 6.1 三层拦截（始终强制执行，不可关闭）

命令 → ① SecurityPolicy.isAllowed()（白名单 + 黑名单 + 15 条危险模式）→ ② IntegrityGuard.validateCommand()（路径保护，接入 Pipeline 指令链）→ ③ 执行

> v0.9.0: 移除所有 `globalEnabled`/`integrityEnabled`/`integrityCheckEnabled` 开关，保护始终生效。IntegrityGuard 之前从未实例化（NoOp 空实现），现已接入 AgentEngine → Pipeline。

### 6.2 Vault

`EncryptedSharedPreferences` + Android Keystore (`security-crypto:1.1.0-alpha06`)。文件级加密 + 应用层加密双层保护。`allowBackup=false` 防止备份泄露。

**容错机制**: 若 Keystore 不可用（部分 OEM 设备已知问题），重试一次后降级到 `InMemoryPreferences`——绝不以明文落盘。`isAvailable` 字段让调用方判断加密是否正常。

**持久化改进** v0.6.1: `savedProviders` 以 JSON 数组加密存储在 `saved_providers_json` 键下，启动时自动恢复。旧版单 Key 格式自动迁移。`removeProvider()` 和 `resetToDefaults()` 同步持久化。

### 6.3 Sanitizer

自动识别并脱敏：OpenAI Key (`sk-proj-*`)、Anthropic Key (`sk-ant-*`)、Google Key (`AIza*`)、Bearer Token、40+ 字符 Base64。

### 6.4 PromptFirewall

Prompt 注入检测防火墙，位于 LLM 调用前最后一道防线。

### 6.5 IntegrityGuard

Fail-secure 完整性守护：启动时校验 APK 签名，检测篡改→安全模式。实现 `IntegrityProvider` 接口，可通过 kernel 的 `SecurityPolicy` 调用。

### 6.6 插件安全

- **APK 签名验证**：安装前校验
- **ProcessBuilder 命令白名单**：禁止 `rm -rf /`、`mkfs`、`dd`、`sudo`
- **HTTPS 优先**：明文 HTTP 触发审计黄牌
- **SSRF 防护**：URL scheme 白名单 + 私有 IP 黑名单 + 禁用重定向
- **文件沙箱**：canonicalFile + workDir 限制 + 符号链接检测 + 50MB 读上限
- **`plugin.audit`**：发布前 7 类安全检查

### 6.7 记忆孪生安全 (v0.12.12+, v0.15.0 重构)

#### 配对安全
- **短码配对协议**: 类似蓝牙配对, 双方独立计算 6 位验证码 (SHA-256(nonceA|nonceB) → 6 digits), 用户肉眼比对防 MITM
- **AES-256 加密通道**: 配对后通过 `AcpCrypto.deriveKey(fpA, fpB)` 派生共享密钥, 后续所有 ACP 消息加密传输
- **信任持久化**: `PromptFirewall.trustWithKey()` → `.trusted` + `.key` 文件, 重启自动恢复
- **配对意向指向性**: 5 连击特定框架图标 = 对特定设备的显式配对意图, 确认弹窗含目标设备名

#### 数据安全
- **账本防盗**: 未配对设备无法访问 `LEDGER_HEAD/PULL/BATCH/ACK` (AcpServer 鉴权)
- **跨链验证**: 接收账本条目前检查 `entries[0].prevHash == localLatest.hash`
- **原子写入**: 所有账本、梦境、身份文档使用 `tmp → rename` 原子写入, 防崩溃损坏
- **内容去重**: 相同哈希的条目自动跳过, 幂等安全

#### 运行时安全
- **频率限制**: CAPABILITY_ANNOUNCE 同 peerId 30 秒内最多 1 次弹窗
- **委派鉴权**: `TWIN_DELEGATE` 需已配对信任才执行
- **解绑清理**: UI 解除孪生时完整清理 `.trusted` / `.key` 文件 + FrameworkPeerStore 记录
- **心跳保活**: 30 秒间隔双向 heartbeat → 90 秒无响应标记离线 → 自动停止向离线节点同步
- **QoS 自适应**: WiFi 全量同步 60s / 移动网络仅关键记忆 300s / 按流量计费暂停自动同步
- **手动 IP 容错**: mDNS 不可用时可通过 `twin.peer.add <ip>` 手动添加节点, 绕过多播隔离

---

## 7. 插件开发

### 7.1 Plugin 接口

```kotlin
interface Plugin {
    val metadata: PluginMetadata
    val commands: Map<String, CommandHandler>
    val uiButtons: List<PluginUiButton> get() = emptyList()
    suspend fun onInstall(ctx: PluginContext) {}
    suspend fun onUninstall() {}
    suspend fun onUpgrade(fromVersion: String) {}
}
```

### 7.2 插件类型

| 类型 | 复杂度 | 适用场景 |
|------|--------|---------|
| **SCRIPT** | 低 | JSON 声明即用，Agent 可自建 |
| **JAR** | 中 | Kotlin 逻辑，有状态，需编译 |
| **AAR** | 高 | 完整 Android 库，含资源/UI |

### 7.3 市场发布

GitHub Pages 托管 `plugins.json`，ETag 缓存 (5 分钟)，SHA256 校验。

信任链：官方 → 信任框架 (SHA256 + 确认) → 公网 (SHA256 + 确认 + 来源标记) → 未验证 (拒绝)

### 7.4 开发流程

`plugin.create` → `plugin.audit` → `plugin.share`，通过 dev-plugin（捆绑在 Shell 中）即可完成。

详细指南见 [PLUGIN_DEV_GUIDE.md](PLUGIN_DEV_GUIDE.md)。

### 7.5 用户即开发者：生态飞轮

SCRIPT 插件的设计——JSON 声明、零编译、Agent 自建——不只是方便开发者的功能，而是**把造碎片的权力交给用碎片的人**。

#### 飞轮

```
用户遇到碎片 → plugin.create 解决 → plugin.share 分享 → 下一个用户受益
                                                        ↓
                                             他遇到另一个碎片 → 继续循环
```

这是维基百科的逻辑：不是靠官方雇佣大量编辑，而是每个遇到问题的人顺手解决，然后所有人受益。

#### 具体例子

一个骑行用户同时用体重秤、功率计、Strava、intervals.icu、TrainingPeaks——每个都很专业，每个之间都不说话。用户想知道"今天的功率体重比"，需要手动走 4-5 步：

```
传统方案：等某个 App 集成所有这些（永远不会发生）
MengPaw方案：
  ├─ plugin-weight    → 体重秤数据（BLE 桥接或手动语音输入，自动记录）
  ├─ plugin-intervals → 训练数据 API
  ├─ plugin-strava    → 户外记录
  └─ 用户问一句 → Agent 跨插件计算 → "今天 FTP 体重比 4.2W/kg，比上周高 0.1。
      体重降了 0.8kg，功率没掉——减重方向正确。"
```

#### 为什么是"用户即开发者"

第一个遇到台灯控制需求的鸿蒙用户会写 plugin-iot 的鸿蒙实现。第一个用码表的骑行用户会写 plugin-cycling。官方不需要写"鸿蒙米家插件"、"骑行数据聚合插件"——**中国数字生态的碎片不会轮到官方来缝完，但用户缝自己遇到的碎片的能力已经内置了。**

信任链确保安全：插件分享需 SHA256 校验 + 用户确认 + 来源标记。不是开源社群的"相信我"，是密码学的"没人能篡改我"。官方 → 信任框架 → 公网 → 拒绝未验证——四层信任，不依赖中心化代码审查。



---

## 8. 开发路线图

- **Phase 1 ✅**: CLI 引擎、3 内置命名空间 (30 命令)、三层安全拦截、会话管理 (含压缩)、LLM 接口 (含降级链)、Prefix Cache、记忆系统、Skill 系统
- **Phase 2 ✅**: Chat UI、前台服务、插件市场 UI、设置 (12 Provider)、Markdown 渲染、BigBang 分词、R8 瘦身
- **Phase 3 ✅**: 独立浏览器 v0.6.0、BrowserBridge 双向桥、45 操控命令、5 浏览器扩展插件
- **Phase 4 ✅**: 微内核拆分 — kernel (44 文件, 纯 JVM) + core (6 文件, Android 适配)、25 插件生态、12 LLM Provider
- **Phase 5 ✅**: 安全加固 (WebView/FsPlugin/NetPlugin/Vault/ACP/Sanitizer)、188 Bug 审计、Agent/UI 层深度修复
- **Phase 6 ✅**: UI 全面重构 — iPad 双栏设置 + 侧栏交互升级 + Per-Agent 模型选择 + Token 统计 + 安全规则 + 设计系统合规 + Loop 模式 + 工作区文件 + 会话修复
- **Phase 7 ⏳**: Device 扩展 — 守护态（哨兵模式）雏形：跨设备 heartbeat 监控 + 离线告警 + NotifyBus 推送；蓝牙/手环传感器桥接；眼镜-摄像头集成
- **Phase 8 ⏳**: 桌面端 MVP — `mengpaw-desktop` 6 文件适配层 + Compose Multiplatform UI + 插件全复用 + 局域网孪生网格直接互通
- **Phase 9 ⏳**: Agent 感官系统 — 手环（健康信号）+ 眼镜（视觉信号）+ 手机（计算中枢）三路融合；Code 扩展 (QuickJS/Python 沙箱)；守护态完整实现（文件完整性/网络异常/物理空间感知）
- **Phase 10 ⏳**: 鸿蒙移植 — kernel + ACP + 插件层复用，鸿蒙分布式设备 API 桥接，ArkUI 重写；在线扩展市场开放

---

## 9. 构建与部署

### 9.1 环境要求

- Android SDK 35 + JDK 17 + Gradle 8.12
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

### 9.1.1 编译性能优化 (v0.17.0)

| 优化项 | 说明 |
|--------|------|
| `org.gradle.caching=true` | 本地构建缓存，增量编译 ~30-50% 加速 |
| `org.gradle.configuration-cache=true` | 配置阶段缓存，跳过 30 模块重复解析 |
| `org.gradle.jvmargs=-Xmx4096m -XX:+UseParallelGC` | 30 模块需 4GB+ 堆，ParallelGC 比 G1 更适合构建 |
| `android.enableJetifier=false` | 全量 AndroidX，Jetifier 零开销 |
| `android.nonTransitiveRClass=true` | R 类非传递，减少编译中间产物 |
| `kotlin.daemon.jvmargs=-Xmx2048m` | Kotlin 编译独立内存池 |
| 统一 Android 配置 | `build.gradle.kts` 根节点 `subprojects` 统一 compileSdk/minSdk/compileOptions，25 插件模块无需重复声明 |
| Release 资源压缩 | Shell + Browser 均启用 `isShrinkResources=true` |
| 版本联动 | Browser versionCode 跟随 `mengpaw.version` 公式计算 |

### 9.2 主要依赖

| 依赖 | 版本 | 位置 |
|------|------|------|
| Kotlin | 2.0.21 | kernel + core |
| kotlinx-serialization-json | 1.7.3 | kernel |
| kotlinx-coroutines | 1.9.0 | kernel (core) / android (shell) |
| ktor-client (core+okhttp) | 3.0.3 | kernel |
| security-crypto | 1.1.0-alpha06 | core |
| Compose BOM | 2024.12.01 | shell / browser / design-system |
| work-runtime-ktx | 2.10.0 | shell |

### 9.3 构建命令

```bash
# 微内核测试 (JVM, 秒级)
./gradlew :mengpaw-kernel:test

# 全部编译
./gradlew :mengpaw-shell:assembleDebug     # Shell APK
./gradlew :mengpaw-browser:assembleDebug   # Browser APK
./gradlew :mengpaw-shell:assembleRelease   # Shell Release (R8)

# 清理
./gradlew clean
```

### 9.4 发布流程

详见 [RELEASE.md](RELEASE.md)。

---

## 10. 项目交接

### 10.1 环境搭建

1. JDK 17 (Amazon Corretto 17 推荐)
2. JAVA_HOME + ANDROID_HOME 环境变量
3. Android SDK 35 (platforms, build-tools, platform-tools, emulator)
4. 克隆 → `./gradlew :mengpaw-kernel:test` → `./gradlew :mengpaw-shell:assembleDebug`

### 10.2 关键配置文件

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` (根) | AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01 |
| `settings.gradle.kts` | 4 核心模块 + 23 插件模块 |
| `mengpaw-kernel/build.gradle.kts` | JVM 模块, kotlinx-serialization, ktor, coroutines-core |
| `mengpaw-core/build.gradle.kts` | Android Library, 依赖 kernel, security-crypto |
| `mengpaw-shell/build.gradle.kts` | Compose, material-icons-extended, work-runtime, 4 捆绑插件, v0.15.2 |
| `mengpaw-browser/build.gradle.kts` | material-icons-core (轻量), version follows mengpaw.version |
| `mengpaw-shell/.../AndroidManifest.xml` | 6 权限, MainActivity, ShellService (foregroundServiceType=dataSync) |
| `mengpaw-browser/.../AndroidManifest.xml` | 2 权限, BrowserActivity (3 intent-filter) |

### 10.3 初始化流程 (v0.8.0 — QwenPaw 风格)

```kotlin
// 1. 崩溃日志
Thread.setDefaultUncaughtExceptionHandler { ... }  // → crash.log + Downloads

// 2. 平台初始化
DataPathsInitializer.initialize(this)  // Context.filesDir → DataPaths.BASE
SysExecutor.init(this)                 // Android 系统命令
KernelLog.setLogger(AndroidLogger())   // 日志适配

// 3. 触发器引擎
TriggerEngine.setContext(this)
TriggerEngine.load()                   // 从 disk 恢复触发器
TriggerEngine.registerSystemWake(this, 10)  // AlarmManager 定时间隔
TriggerEngine.refreshCronAlarm()       // 注册下一次 Cron 唤醒

// 4. 前台服务 (通知栏常驻)
ShellService.start(this)   // startForeground + WakeLock

// 5. UI 层: 启动时自动恢复配置 + 触发 AgentRuntime
//    退出设置时 applyConfiguration (轻量, 无副作用)
//    用户发第一条消息 → Agent 调用 LLM
```

**设计原则**: Agent 不自动启动。安装→配置→用户驱动。和 QwenPaw 一致。

### 10.4 代码规范

- 包命名：`com.mengpaw.{模块}.{功能}`
- 类大驼峰，函数小驼峰
- UI 文字全部中文（Strings.kt 本地化）
- 注释中文
- 禁止 `!!` 强制解包
- 所有文件 IO 必须 try/catch
- SPDX 版权头：所有 `.kt` / `.kts` 文件

### 10.5 已知问题

| 问题 | 优先级 | 说明 |
|------|--------|------|
| Kernel 测试 5 个预存失败 | 低 | Sanitizer 断言过时 (4) + AgentEngine 语言断言 (1) |
| proc 命令未实现 | 低 | SecurityPolicy 已拦截 |
| ClipboardExecutor 内存存储 | 低 | Android 环境兼容 |
| NotificationExecutor stub | 中 | 需 NotificationListenerService |
| SelfPlugin 覆盖 kernel SelfExecutor | 低 | 4 个命令被插件版本覆盖，其余 10 个不受影响 |

> v0.16.0: 内置插件版本号清空; BM25 命令检索上线; 循环模式 REACT 默认; 6 项性能优化完成

---

## 附录 A: 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| **0.18.3** | 2026-07-29 | **UI重构 + 暗色模式Arco规范 + 主题色安全加固** — A) 斜杠命令改为悬浮按钮排列于左侧边栏右侧, 无容器无阴影 B) 框架状态自动忙碌切换, 移除恢复自动切换按钮 C) 暗色模式按Arco Design规范全面调整 (bg-1~bg-5层级, text-1~text-4透明度体系) D) 主题色自定义仅亮色模式生效, 排除暗色模式泄露; AgentTheme 移除无效深色字段 E) 设置页重构: 框架设置→API供应商, 模型选择移入智能体设置, 供应商卡片支持模型下拉选择+API自动拉取 F) 侧边栏滑动关闭手势 G) Qwen→DashScope(dashiscope) H) 后台运行策略移至后台运行区排首位 |
| **0.17.0** | 2026-07-27 | **持久会话 + 结构化压缩 + 工具裁剪** — 四框架对话上下文融合: A) Claude Code 模式: AgentEngine.conversationSessionId 持久复用 Session, LLM 每次请求看到完整历史而非孤立消息, 移除 "[上一任务已结束]" 上下文切割边界 B) QwenPaw 模式: SessionManager 五字段结构化摘要(Goal/Progress/KeyDecisions/NextSteps/CriticalContext), 压缩前归档原始消息到 dialog/YYYY-MM-DD.jsonl 零数据丢失 C) QwenPaw 模式: pruneToolResult 双阈值(≤3步30KB/>3步2KB), 完整输出存 tool_results/{uuid}.txt, 5天清理 D) OpenClaw 模式: engine.newConversation() 重置持久会话, UI newSession 联动 E) DataPaths +dialogArchiveDir/+toolResultsDir, SessionManager +agentName/+specificSessionId |
| **0.16.0** | 2026-07-27 | **三层自适应调度 + BM25 命令检索 + 6 项性能优化** — A: 循环模式重构 (QwenPaw 风格默认 REACT → 自动检测升级 GOAL/MISSION, Claude Code 风格复杂度评分, UI AssistChip 自动标注) B: BM25 命令搜索引擎 (self.search, ~50条内置命令双语同义词表, μs 级检索, bigram 分词, 插件激活/卸载自动联动) C: 插件关键词脚手架 (CommandKeywords 数据类, plugin.create 模板内置, plugin.audit 检查, plugin.keywords 查看) D: 6 项性能优化 (Prompt 缓存按文件粒度失效/中记忆批量合并/会话增量持久化/启动懒加载/协程池分离/GC 压力优化) E: 内置插件版本号清空 (随壳更新) |
| **0.15.2** | 2026-07-26 | **6 审计问题修复** — 缓存失效(路径匹配) + 缓存key(检查前置) + Plan进度(中英双语边界) + 错误消息(LlmApiException) + 容器高度(Step编号/观察缺失) + 提示词(恢复插件发现示例) + MCP插件解耦BrowserBridge + 超时120s |
| **0.15.0** | 2026-07-25 | **记忆孪生全链路重构 + 记忆三轨制** — A: 三层十二问审计 → 14 项全修 (心跳保活/QoS自适应/手动IP/配对指引/ACP就绪轮询/syncWithPeer返真值/命令命名空间修复/解绑UI/错误诊断/原子写入补全) B: 记忆架构重构 → 三轨制: 长期记忆(memory/memory.md, 仅三种来源, 注入系统提示词) + 中期记忆(memory/memory_{date}.md, 按日分片, 不注入提示词, 梦境按日压缩) + 项目记忆(memory/project_{name}_memory.md, 里程碑/闭环时总结, 可复用方法论) + agent.memory.keep/record/mid/project 命令族 |
| **0.14.1** | 2026-07-24 | **验证反馈修复** — 底部栏 IconButton→pointerInput + 插件页 registerBuiltins 时序 + DexClassLoader 多类名降级 + 空会话清理 |
| **0.13.0** | 2026-07-24 | **捆绑插件补齐 + 循环检测增强 + 会话去重 + 工具输出完整展示 + Claude Bridge 移除** — 10 插件捆绑启动 (net/fs/self/clipboard/notification/memory-twin 补齐) + 连续失败 5 次自动终止 + `restoreCurrentSession` 修复重复会话 + TraceStepItem 可展开完整输出 + 左侧栏手机模式背景修复 + `plugin.marketplace` 加入系统提示词 + hardkey Enter 双触发修复 + versionCode 公式修正 |
| **0.12.12** | 2026-07-24 | 记忆孪生 (6 BUG 修复 + 5连击激活 + ACP P2P 配对 + 账本自动同步) + 开发文档重构 · 详见 `docs/lessons.md` |
| **0.11.3** | 2026-07-23 | **嵌套滚动根除 + 视觉表格 + commonmark 引擎全覆盖** — MarkdownText nestedScroll 参数 + 表格 widthIn(min) 列宽 + Image/HtmlBlock/嵌套列表/TableBody AST 全量转换 + ShellService deleteChannel SecurityException |
| **0.11.0** | 2026-07-23 | **线程架构优化 + commonmark AST 引擎** — P0: Column+verticalScroll 替代 LazyColumn, P1: AgentDocs→Dispatchers.IO, commonmark-java 替代手写解析器, 视觉表格渲染 |
| **0.10.0** | 2026-07-23 | **框架协议插件 + 侧边栏交互 + 主题系统** — 框架发现插件 (mDNS 局域网注册/扫描/指纹) + 侧边栏头像打开 + 全局滑动手势 + 智能体名片重排 (工作区滚动) + 框架名片 (名称/版本/备注/Agent列表) + 亮/暗/跟随系统三档主题 + GeoRouter 系统时区判断 + 插件管理页精简 + PAD 插件移除 + 启动页品牌 Logo 替换 |
| **0.9.1** | 2026-07-22 | **品牌焕新 + 扩展功能重构** — 主题色更新 (#0E4397/#FC5185) + 启动页品牌 Logo 替换 + 扩展面板三区重构 (文件提交/执行模式/插件工具) + `/Mission` `/Research` `/Translate` `/Dream` 斜杠命令标签 + `@agent` 自动补全 + 气泡模式标注 + 面板图标自定义排序 |
| **0.9.0** | 2026-07-22 | **安全强化 + 模板文件化** — 三大安全保护去除开关/强制启用 + IntegrityGuard 接入 Pipeline 指令链 (之前从未实例化) + MD 模板从 Kotlin 硬编码字符串改为 assets .md 文件 (7 个模板 ~350 行代码删除) + 智能体专属工具/技能 (全局池安装/Agent 自装/用户提供路径) + 设置页文案重构 (全局工具/智能体工具) + 废弃插件目录物理删除 |
| **0.8.4** | 2026-07-22 | **会话管理增强** — 独立会话文件 + 切换恢复 (`switchToSession`) + 跨会话搜索 (`agent.sessions`) + 原子写入防损坏 + 引擎可靠性修复 (安全命令白名单/循环检测优化/状态重置) + UI 升级 (自适应宽度/自动定位/真实头像/Markdown Heading) + 构建统一版本号 (mengpaw.version) |
| **0.8.0** | 2026-07-22 | **重大架构重构** — UI/运行时分离 (AgentRuntime) + QwenPaw 风格初始化 + 会话完整持久化 (30s 自动保存 + 思考链) + 智能体管理 (长按/删除/框架) + 输入优化 (Enter 发送/聚焦) + 20+ 崩溃/ANR 修复 + Android 13-17 全版本 + 5大国产 OEM 适配 + 系统提示词重构 |
| **0.7.0** | 2026-07-22 | Android CLI 全功能 (11→38 命令) + 全类型 Skill 引擎 + CRON 触发器 + LIFETIME 心跳 + 会话持久化 + 智能体名片 + API 模型更新 + Boost 自动启动 |
| **0.6.2** | 2026-07-21 | Agent 逻辑修复 — 14 Bug 修复: DreamEngine 参数混淆/大小写/单位错误/dreamLog 缺失; AgentDocManager 索引损坏/ID 解析/数据丢失; Goal 模式上下文丢失; snipStaleToolResults 不生效; Pipeline 缓存; DeepSeek-Chat 解析死循环; RubricGate 改进; API 模型更新 (8 Provider 至最新) |
| **0.6.1** | 2026-07-21 | 内核功能补全 — Goal/Mission/Mission+ 内置模式 (RubricGate LLM 完成评估) + Agent→User 推送 (NotifyBus) + self 命名空间扩展 (+5 命令: tools/time/notify) + fs 扩展 (+grep/glob) + QwenPaw 4 Skills 移植 + API Key 持久化修复 + Provider 热更新 + Android 权限补全 (17 项) + Vault 安全加固 (绝不明文) + ProGuard Tink keep 规则 |
| **0.6.0** | 2026-07-21 | UI 全面重构 — iPad 双栏设置 + 侧栏交互升级(左滑/长按多选/框架状态) + Per-Agent 模型选择 + Token 统计折线图 + 安全规则页 + WowBlue 启动动画 + 设计系统合规(硬编码色值清零) + 会话修复 + 通知栏常驻 |
| **0.5.0** | 2026-07-21 | 微内核拆分 — kernel (44 文件, 纯 JVM) + core (6 文件, Android 适配) + 25 插件生态 |
| **0.4.0** | 2026-07-21 | 安全加固 + 全项目修复 + 188 Bug 审计 + 89 项修复 + 模拟器验证零闪退 |
| 0.3.x | 2026-07-20 | 25 插件生态 + 浏览器操控 + 多模态 + 12 LLM Provider + Mission/Worker/Verifier + BrowserBridge |
| 0.2.2 | 2026-07-19 | DataPaths 动态初始化 + 4 轮安全审计 + plugin-dev CLI |
| 0.2.1 | 2026-07-19 | 多智能体 + 缓存优化 + Dream 模式 + Markdown/Emoji + BigBangPopup |
| 0.2.0-alpha | 2026-07-16~17 | 微内核+插件架构 + ACP + MCP + 触发器引擎 + 深浅主题 |
| 0.1.0-alpha | 2026-07-13 | CLI 引擎 + Chat UI + 独立浏览器 + R8 瘦身 |

## 附录 B: 审校记录

| 日期 | 审校项 | 结果 |
|------|--------|------|
| 2026-07-26 | v0.15.2 功能闭环审计 | PromptEngine 三层十二问审计 → 6 问题全修: 缓存失效(路径前缀) + 缓存key(检查前置+docCache非空守卫) + Plan进度(中英双语边界标记) + 错误消息(LlmApiException替双重bodyAsText) + 容器高度(Step编号恢复+观察缺失修复) + 提示词(恢复插件发现few-shot) + MCP插件BrowserBridge解耦(toolExecutor委托) + RemoteApi超时120s。编译通过, 测试通过。 |
| 2026-07-25 | v0.15.0 记忆孪生全链路审计 | 三层十二问审计 → 14 问题全修: P0×6 (系统提示词/配对指引/ACP就绪/syncWithPeer/mDNS单点/命令命名空间), P1×6 (QoS/心跳/解绑UI/错误诊断/同步反馈/self.tools覆盖), P2×2 (协议版本/原子写入)。8 文件修改, 626 行新增, 编译通过, 测试通过。 |
| 2026-07-21 | v0.6.0 设计系统合规 | 11 个 UI 文件硬编码色值清零, 全部替换为 ArcoColors token |
| 2026-07-21 | v0.6.0 编译验证 | clean build 4m10s 通过, 15 文件修改, 编译问题 10 项已记录 |
| 2026-07-21 | 微内核拆分验证 | kernel (44文件) + core (6文件) 编译通过, 25插件编译通过, 83/88 测试通过 |
| 2026-07-21 | 开发文档全量重构 | 基于微内核架构重写，修正全部数据，移除 TV 模块 |
| 2026-07-20 | v0.3.0 编译审查 | 7 个编译错误修复 |
| 2026-07-20 | 模型切换审查 | 15 stale state bug, 9 修复 |
| 2026-07-20 | 闪退根因审查 | 13 问题全修复 |
| 2026-07-19 | Crash 漏洞四审四校 | DataPaths/IO/EventReceiver/HttpClient/状态串扰/!! 全部修复 |
| 2026-07-23 | v0.11.3 全量审校 | ProGuard 规则修正 (kernel 包路径) + !! 清零 + 文件 IO/协程 try/catch 补全 + 文档命令计数修正 (self 14→13, agent 11→12, sys 39, plugin 10→11+auto, skill 4→7, inspector 4→6) + 僵尸目录清理 (agent-loop/agent-mission) |
| 2026-07-24 | v0.12.12 记忆孪生 | 6 BUG 修复 (PluginManager版本/startListener/JSON转义/防火墙/inbox轮询/自动恢复) + 5连击激活 + ACP P2P 配对 + 账本自动同步 · 详见 `docs/lessons.md` |
| 2026-07-24 | v0.13.0 全量审校 | 10 插件捆绑补齐 + 循环检测增强 (连续失败) + 会话去重 + 工具输出完整展示 + Claude Bridge 移除 + hardkey Enter 修复 + versionCode 公式修正 · 全部遗留问题已于 v0.14.0 ~ v0.15.2 解决 |

---

## 附录 C: v0.12.12 核心经验

### 分布式调试
- 每轮调试: 改代码 → 构建 → 2台设备安装 → 双方激活 → 配对 → 查日志
- `adb logcat -s MengPawTwin` 集中所有孪生日志, 一个 tag 看全链路
- 先验证端口 (`curl`), 再验证消息, 最后验证 UI

### 关键 BUG 模式
1. **构造不完全**: `AcpHttpTransport()` 不监听, `startListener()` 必须显式调用
2. **默认值 != 真实值**: `PluginManager("0.2.0")` 需注入 `CORE_VERSION`
3. **手写 JSON 必出错**: 用 `org.json.JSONObject` 或序列化库
4. **Compose 不感知文件系统**: inbox 文件需轮询检查
5. **防火墙拦截自己的协议**: 信任建立类消息需绕过安全策略

### 记忆孪生架构
- `plugin-memory-twin` (10 文件, ~2100 行) — 首个 `AcpHandler` 实现
- 哈希链账本 (SHA-256) + ACP P2P + inbox 文件式触发
- 5连击隐藏手势 → 发起方弹窗 → 接收方弹窗 = 三重安全门槛

---

*文档结束 · 最后更新: 2026-07-30 (v0.18.3) · 本版新增: UI重构 + 暗色模式Arco规范 + 主题色安全加固 + 设置页重构*
