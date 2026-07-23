# MengPaw 开发文档

> 📄 灵感来源: [ATTRIBUTIONS.md](ATTRIBUTIONS.md) — QwenPaw · Hermes · OpenClaw · Claude Code · ReAct · ComfyUI · LangChain · CrewAI · Dify · Tavily · Arco Design · Material Design 3

> **版本**: 0.10.0 | **更新**: 2026-07-23 | **架构**: 微内核 + AgentRuntime (UI/运行时分离) + 23 插件 + 框架协议插件 + 品牌焕新 + 扩展功能重构 + 侧边栏交互 + 智能体/框架名片

---

## 1. 项目概述

MengPaw（檬爪）— 微内核 + 插件架构的 Android Agent 框架。核心理念：**Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

| 特征 | 说明 |
|------|------|
| 微内核 | `mengpaw-kernel` — 纯 Kotlin/JVM 模块，零 Android 依赖，CLI/LLM/安全/会话/插件框架/Goal-Mission 模式全部可脱离 Android 测试 |
| 适配层 | `mengpaw-core` — 仅 6 个源文件，提供 Android 桥接（Vault 加密存储 / IntegrityGuard / SysExecutor） |
| 插件同级 | 内置功能 (`sys`) 与外挂插件同等地位，均实现 `Plugin` 接口，均只依赖 kernel |
| 零 Python | 纯 Kotlin，无 Python 运行时 |
| 多通道 | AIDL（系统集成）/ Unix Socket（Termux）/ HTTP（调试） |
| 独立浏览器 | `mengpaw-browser` (v0.4.0)，Intent 互通，22 条浏览器操控命令 |
| 多模型 | 12 LLM Provider — OpenAI / DeepSeek / Kimi / GLM / Qwen / Grok / 火山引擎 / OpenModel / Self-Hosted / 自定义 |
| 插件市场 | GitHub Pages 托管 `plugins.json`，ETag 缓存，SHA256 校验 |
| Agent 自我升级 | `plugin.marketplace` → `plugin.search` → `plugin.install` → 命令即可用 |
| 内置 Loop 模式 | Goal / Mission / Mission+ 三种模式直接内置在 AgentEngine，含 RubricGate 自动完成评估 |
| Agent 推送 | `notify.message` / `notify.banner` — Agent 主动向用户推送消息和横幅 |

适用场景：自动化研究、RPA 替代、数字助手、渗透测试、边缘 AI。

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
│  plugins/ (23 模块, 同级, 均只依赖 kernel)         │  ← 插件层
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
| mengpaw-kernel | JVM Library | 46 | 0.8.4 | 微内核：纯 Kotlin，零 Android 依赖 |
| mengpaw-core | Android Library | 6 | — | Android 适配层：Vault / IntegrityGuard / SysExecutor |
| mengpaw-design-system | Android Library | 5 | — | Arco 主题 / Markdown 渲染 / 基础组件 |
| mengpaw-shell | APK | 25 | 0.9.1 (vc=91) | 主应用：AgentRuntime + Chat UI + 设置 + 会话管理 (独立持久化/切换恢复/跨会话搜索) + 智能体管理 + 扩展功能重构 |
| mengpaw-browser | APK | 5 | 0.4.0 (vc=6) | 独立浏览器 + BrowserBridge + 22 操控命令 |

### 2.3 内置命名空间（在 kernel 中，始终可用）

| 命名空间 | 源文件 | 命令数 | 职责 |
|---------|--------|--------|------|
| `self` | SelfExecutor.kt | 14 | Agent 自省 (status/config/stats/version/avatar/theme/mcp/trigger/acp/tools/time/notify.message/notify.banner) |
| `agent` | AgentExecutor.kt | 12 | 文档管理 (docs/memory/memory.record/cli/profile/soul/audit/browser-tools/dream/cleanup/storage/sessions) |
| `plugin` | PluginExecutor + DevPlugin | 10 + 4 | 插件管理 (marketplace/search/install/uninstall/list/info/enable/disable/update/upgrade + create/audit/share/examples) |
`framework` | FrameworkPlugin | 6 | 框架发现 (discover/peers/trust/untrust/info/ping) |

> `sys` 命名空间 (11 命令) 在 `mengpaw-core` 中实现，通过 `additionalNamespaces` 注入 AgentEngine，与其他插件同级。

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

---

## 3. 模块详解

### 3.1 mengpaw-kernel（微内核，46 文件）

| 包 | 文件数 | 关键类 |
|----|--------|--------|
| `cli/` | 4 | CliInterpreter, CommandRegistry, CommandExecutor, Pipeline |
| `llm/` | 6 | AdaptiveLlmProvider, LlmProvider, LlmRequestBuilder, PromptEngine, RemoteApi, TranslateMiddleware |
| `session/` | 3 | SessionManager, History, Checkpoint |
| `plugin/` | 4 | Plugin, PluginManager, PluginExecutor, PluginMarketplaceClient |
| `agent/` | 9 | AgentDocManager, AgentDocs, AgentExecutor, AgentMiddleware, AgentProfile, DreamEngine, PromptBuilder, ScrollContext, GoalSession |
| `security/` | 4 | Sanitizer, SecurityPolicy, PromptFirewall, IntegrityProvider |
| `mcp/` | 2 | McpServer, McpClient |
| `acp/` | 4 | AcpProtocol, AcpServer, AcpCrypto, AcpTransport |
| `mission/` | 1 | MissionMonitor |
| `error/` | 1 | ErrorCollector |
| `extension/` | 1 | ManifestParser |
| `trigger/` | 1 | TriggerEngine |
| `namespace/` | 3 | SelfExecutor, ScreenshotManager, NotifyBus |
| 根 | 3 | AgentEngine, DataPaths, KernelLog |

> **v0.6.1 新增**: `GoalSession.kt` (GoalSession + RubricEvaluator + MissionSubtask), `NotifyBus.kt` (Agent→User 推送总线), SelfExecutor +5 命令

### 3.2 mengpaw-core（Android 适配层，6 文件）

| 文件 | 职责 |
|------|------|
| `security/Vault.kt` | API Key 加密存储 (EncryptedSharedPreferences + Android Keystore) |
| `security/IntegrityGuard.kt` | APK 签名校验，实现 `IntegrityProvider` 接口 |
| `security/StorageMonitor.kt` | 磁盘空间监控 (android.os.StatFs) |
| `namespace/SysExecutor.kt` | 系统信息命令 (11 个，反射 Android API) |
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

### 3.4 mengpaw-browser（独立浏览器，5 文件）

| 文件 | 职责 |
|------|------|
| `BrowserActivity.kt` | 完整浏览器（后退/前进/刷新/URL 栏/进度条），3 组 intent-filter |
| `bridge/BrowserBridge.kt` | Java↔JS 双向桥 |
| `plugin/BuiltinBrowserPlugin.kt` | 浏览器插件 (22 命令) |
| `plugin/BrowserPlugin.kt` | 浏览器插件接口 |
| `plugin/BrowserPluginRegistry.kt` | 插件注册表 |

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

### 3.6 构建配置

| 配置 | Shell | Browser | Core | Kernel |
|------|-------|---------|------|--------|
| 插件类型 | com.android.application | com.android.application | com.android.library | kotlin("jvm") |
| compileSdk | 35 | 35 | 35 | — |
| minSdk | 26 | 26 | 26 | — |
| targetSdk | 35 | 35 | — | — |
| versionName | 0.10.0 | 0.4.0 | — | 0.10.0 |
| versionCode | 1000 | 6 | — | — |
| R8 | Release 启用 | Release 启用 | 关闭(库模块) | — |

**Shell 权限** (17 项):
- 网络: INTERNET, ACCESS_NETWORK_STATE
- 保活: FOREGROUND_SERVICE, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK
- 悬浮窗: SYSTEM_ALERT_WINDOW
- 内置工具: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, CAMERA, QUERY_ALL_PACKAGES
- 插件: REQUEST_INSTALL_PACKAGES
- 文件/媒体: READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE
- 未来扩展: RECORD_AUDIO, VIBRATE

**Browser 权限**: INTERNET, ACCESS_NETWORK_STATE

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

---

## 5. CLI 规范

### 5.1 内置命名空间（kernel）

#### self — Agent 自省 (14)
`status` | `config [key=value]` | `stats` | `version` | `avatar` | `theme` | `mcp` | `trigger` | `acp` | `tools [namespace]` | `time [format]` | `notify.message <text>` | `notify.banner <text> [--level]`

> **v0.6.1 新增**: `tools` — 按命名空间列出所有可用命令；`time` — 获取当前时间 (支持 iso/date/time/timestamp)；`notify.message` — Agent 推送消息到聊天；`notify.banner` — Agent 推送横幅 (支持 info/success/warn/error)

#### agent — 文档管理 (11)
`docs` | `memory [query]` | `memory.record <content>` | `cli` | `profile` | `soul` | `audit` | `browser-tools` | `dream` | `cleanup` | `storage` | `sessions [keyword] [limit]`

> **v0.8.4 新增**: `sessions` — 跨会话搜索历史记录，支持关键词过滤和条数限制

#### plugin — 插件管理 (10 + 4)
**内核 (10)**：`marketplace [--refresh]` | `search <query>` | `install <id>` | `uninstall <id>` | `list` | `info <id>` | `enable <id>` | `disable <id>` | `update <id>` | `upgrade --all`

**dev 插件扩展 (4)**：`create --type script|jar --name <name>` | `audit <id>` | `share <id> --to <target>` | `examples`

#### sys — Android 系统 (38 命令，通过 Android 适配层注入)

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

**权限 (2)**: `permission.list` | `permission.request <name>`

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

#### skill — 技能 (4)
`ls` | `run <name>` | `enable <name>` | `disable <name>`

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

#### inspector — 元素检查器 (4)
`start` | `stop` | `select <selector>` | `inspect`

### 5.3 浏览器内置命令 (browser.*, 22)

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

---

## 8. 开发路线图

- **Phase 1 ✅**: CLI 引擎、3 内置命名空间 (30 命令)、三层安全拦截、会话管理 (含压缩)、LLM 接口 (含降级链)、Prefix Cache、记忆系统、Skill 系统
- **Phase 2 ✅**: Chat UI、前台服务、插件市场 UI、设置 (12 Provider)、Markdown 渲染、BigBang 分词、R8 瘦身
- **Phase 3 ✅**: 独立浏览器 (0.3.0)、BrowserBridge 双向桥、22 操控命令、5 浏览器扩展插件
- **Phase 4 ✅**: 微内核拆分 — kernel (44 文件, 纯 JVM) + core (6 文件, Android 适配)、25 插件生态、12 LLM Provider
- **Phase 5 ✅**: 安全加固 (WebView/FsPlugin/NetPlugin/Vault/ACP/Sanitizer)、188 Bug 审计、Agent/UI 层深度修复
- **Phase 6 ✅**: UI 全面重构 — iPad 双栏设置 + 侧栏交互升级 + Per-Agent 模型选择 + Token 统计 + 安全规则 + 设计系统合规 + Loop 模式 + 工作区文件 + 会话修复
- **Phase 7 ⏳**: Device 扩展 (AccessibilityService/MediaProjection/InputManager)
- **Phase 8 ⏳**: Code 扩展 (QuickJS/Python 沙箱)、CLI Python 工具、在线扩展市场

---

## 9. 构建与部署

### 9.1 环境要求

- Android SDK 35 + JDK 17 + Gradle 8.12
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

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
| `mengpaw-shell/build.gradle.kts` | Compose, material-icons-extended, work-runtime, 4 捆绑插件, v0.6.0 |
| `mengpaw-browser/build.gradle.kts` | material-icons-core (轻量), v0.4.0 |
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

> v0.9.0: MD 模板文件化（~350 行硬编码字符串删除）；三大安全保护强制启用 + IntegrityGuard 接入 Pipeline；废弃插件目录物理删除；设置页文案重构
> v0.6.1: 所有 6 项 settings-pending 已解决；Goal/Mission/Mission+ 模式已内置；4 个 QwenPaw Skills 已移植

---

## 附录 A: 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
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
| 2026-07-21 | v0.6.0 设计系统合规 | 11 个 UI 文件硬编码色值清零, 全部替换为 ArcoColors token |
| 2026-07-21 | v0.6.0 编译验证 | clean build 4m10s 通过, 15 文件修改, 编译问题 10 项已记录 |
| 2026-07-21 | 微内核拆分验证 | kernel (44文件) + core (6文件) 编译通过, 25插件编译通过, 83/88 测试通过 |
| 2026-07-21 | 开发文档全量重构 | 基于微内核架构重写，修正全部数据，移除 TV 模块 |
| 2026-07-20 | v0.3.0 编译审查 | 7 个编译错误修复 |
| 2026-07-20 | 模型切换审查 | 15 stale state bug, 9 修复 |
| 2026-07-20 | 闪退根因审查 | 13 问题全修复 |
| 2026-07-19 | Crash 漏洞四审四校 | DataPaths/IO/EventReceiver/HttpClient/状态串扰/!! 全部修复 |

---

*文档结束 · 最后更新: 2026-07-23 (v0.10.0)*
