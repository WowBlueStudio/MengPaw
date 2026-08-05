# MengPaw 开发文档

> 📄 灵感来源: [ATTRIBUTIONS.md](ATTRIBUTIONS.md) — QwenPaw · Hermes · OpenClaw · Claude Code · ReAct · ComfyUI · LangChain · CrewAI · Dify · Tavily · Arco Design · Material Design 3

> **版本**: 0.30.0 | **更新**: 2026-08-05 | **架构**: 微内核(82文件) + AgentRuntime + 21插件模块(14内置随壳更新) + 双许可(社区AGPL + 商业授权) + 连接器拆分独立仓库(MIT) + 单轨记忆(三轨持有全部记忆) + 进化系统(evolution.*) + BM25命令检索(self.search) + 端口单一事实源(self.ports) + 五模式自适应调度(REACT/GOAL/MISSION/SWARM/FLEET) + 8斜杠模式菜单(modes.md) + 孪生工作区文件同步 + 梦境管道(读→备份→{date}_dream.md→到期删除) + 持久会话上下文(Claude Code模式) + 结构化压缩归档(QwenPaw模式) + 工具结果裁剪(QwenPaw模式) + 6项性能优化 + 浏览器 v0.7.0

---

## 1. 项目概述

### 1.0 产品定位

微内核 + 插件架构的 Agent 框架。不造轮子造轮毂——通过插件把已有的碎片桥接成一个整体。核心理念：Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。

### 1.1 架构定位

MengPaw（檬爪）— 微内核 + 插件架构的 Agent 框架。当前运行于 Android，架构设计上可移植到 Linux / Windows / macOS / 鸿蒙。

| 特征 | 说明 |
|------|------|
| 微内核 | `mengpaw-kernel` — 纯 Kotlin/JVM 模块，46 文件，零 Android 依赖，CLI/LLM/安全/会话/插件框架/Goal-Fleet 模式全部可脱离 Android 测试 |
| 适配层 | `mengpaw-core` — 仅 6 个源文件，提供 Android 桥接（Vault 加密存储 / IntegrityGuard / SysExecutor）。移植到新平台只需重写这 6 个文件 |
| 插件同级 | 内置功能 (`sys`) 与外挂插件同等地位，均实现 `Plugin` 接口，均只依赖 kernel |
| 零 Python | 纯 Kotlin，无 Python 运行时 |
| 多通道 | AIDL（系统集成）/ Unix Socket（Termux）/ HTTP（调试） |
| 独立浏览器 | `mengpaw-browser` v0.7.0，Intent 互通，45 条浏览器操控命令 |
| 多模型 | 12 LLM Provider — OpenAI / DeepSeek / Kimi / GLM / Qwen / Grok / 火山引擎 / OpenModel / Self-Hosted / 自定义 |
| 插件市场 | raw 直读 `plugins.json`（GitHub raw / Gitee raw 双源），ETag 缓存，SHA256 校验 |
| 记忆孪生 | v0.15.0 — 跨设备 Agent 记忆同步 + 哈希链账本 + 短码配对 + 心跳保活 + QoS 自适应 + 手动 IP 发现 (plugin-memory-twin v0.2) |
| Agent 自我升级 | `plugin.marketplace` → `plugin.search` → `plugin.install` → 命令即可用 |
| 内置 Loop 模式 | Goal / Fleet / Fleet+ 三种模式直接内置在 AgentEngine，含 RubricGate 自动完成评估 |
| Agent 推送 | `notify.message` / `notify.banner` — Agent 主动向用户推送消息和横幅 |


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
│  AgentEngine · Goal/Fleet/Swarm · MCP · ACP     │
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
| mengpaw-kernel | JVM Library | 82 | 0.30.0 | 微内核：纯 Kotlin，零 Android 依赖 |
| mengpaw-core | Android Library | 21 | — | Android 适配层：Vault / IntegrityGuard / SysExecutor |
| mengpaw-design-system | Android Library | 6 | — | Arco 主题 / Markdown 渲染 / 基础组件 |
| mengpaw-shell | APK | 65 | 0.30.0 (vc=30000) | 主应用：AgentRuntime + Chat UI + 设置 + 会话管理 (独立持久化/切换恢复/跨会话搜索) + 智能体管理 + 扩展功能重构 |
| mengpaw-browser | APK | 33 | 0.7.0 (vc=9) | 5标签预渲染 + 会话持久化 + 收藏夹 + App横幅屏蔽 + 平板标签栏白色主题 + 手机标签对话框 + 暗色模式 + file:// + WebView版本 + 33文件架构 |

### 2.3 内置命名空间（在 kernel 中，始终可用）

| 命名空间 | 源文件 | 命令数 | 职责 |
|---------|--------|--------|------|
| `self` | SelfExecutor.kt | 16 | Agent 自我管理 (status/config/stats/version/avatar/theme/mcp/trigger/acp/tools/ports/search/search.stats/time/notify.message/notify.banner) |
| `agent` | AgentExecutor.kt | 39 | 文档(6) + 记忆三轨(18) + 其他(5) + 会话(4) + 工作区文件(6) |
| `plugin` | PluginExecutor + DevPlugin | 12 + 6 | 插件管理 (marketplace/search/install/uninstall/list/info/enable/disable/update/upgrade/auto/verify + create/audit/share/examples/keywords/guide) |
| `framework` | FrameworkPlugin | 11 | 框架通信 (discover/add/peers/trust/untrust/info/ping/connect/call/disconnect/adapters) [↔ 同捆插件 plugin-framework] |
| `evolution` | EvolutionExecutor.kt | 5 | 智能体进化 (audit/report/learn.command/reactions/mark-corrected) [↔ 同捆插件 plugin-evolution 提供默认实现] |

> `sys` 命名空间 (40 命令) 在 `mengpaw-core` 中实现；`framework` 由 `plugin-framework` 捆绑插件提供。均通过 `additionalNamespaces` 注入 AgentEngine，与其他插件同级。`evolution` 命名空间在内核注册 (PipelineManager)，默认实现由同捆插件 plugin-evolution 注册为 EvolutionProvider SPI。

### 2.4 依赖关系

```
mengpaw-shell
  ├── mengpaw-kernel (微内核)
  ├── mengpaw-core (Android 适配)
  ├── mengpaw-design-system (主题)
  └── 12 捆绑插件: skill / framework / dev / fs / net / clipboard /
      memory-twin / root / hermes(tribe) / agent-tools / dream / evolution
      (self 与 memory 已融入内核, 非插件)

mengpaw-browser
  ├── mengpaw-kernel
  ├── mengpaw-core
  └── mengpaw-design-system

plugins/ (21 模块)
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

AgentEngine 支持五种执行模式：

| 模式 | 方法 | 说明 |
|------|------|------|
| **ReAct** | `run()` | Thought → Action → Observation 标准模式，含循环检测和最大步数限制 |
| **Plan-Execute** | `runWithPlan()` | LLM 分解任务为 3-7 步计划，逐步执行，每步独立 mini ReAct 循环 |
| **Goal** | `runWithGoal()` | 单目标驱动 + RubricGate 自动完成评估（参考 QwenPaw GoalMode） |
| **Fleet (步坦协同模式, Combined Arms Mode)** | `runWithFleet()` | 装甲集群推进+步兵协同清剿：多 Agent 编队协同，跨设备分布式执行复杂任务（转发到火种模式，默认单模型） |
| **火种 (Swarm)** | `runWithSwarm()` | 星星之火可以燎原：规划器拆解 → 并行 Worker（可按角色混合模型）→ Verifier 验证 → 合成器输出。JIT 三闸门（总预算/WIP 并行/单任务）+ Andon 失败协议 + 零待命 Worker。详见 [docs/swarm-design.md](docs/swarm-design.md) |

**Goal 模式架构**:
```
runWithGoal(task, maxTurns, maxTokens)
  ├── GoalSession — 目标状态 (goal/active/iteration/tokensUsed/verdict)
  ├── GoalTurnGate — 迭代计数 + 上限检查
  ├── GoalBudgetGate — token 预算检查
  └── RubricGate — LLM 评估 "目标完成了吗?" → YES=结束 / NO=继续
```

**Fleet (步坦协同) 模式架构**:
```
runWithFleet(task, maxSubtasks, maxStepsPerSubtask)
  ├── Phase 1: LLM 拆解 → List<FleetSubtask>
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


---

## 3. 模块详解

### 3.1 mengpaw-kernel（微内核，82 文件）

| 包 | 文件数 | 关键类 |
|----|--------|--------|
| `agent/` | 22 | AgentExecutor, AgentEngineTypes, MissionModeExecutor, GoalModeExecutor, PlanModeExecutor, SwarmModeExecutor, DreamEngine, ToolResultManager, AgentProfile, PromptBuilder 等 |
| `llm/` | 9 | AdaptiveLlmProvider, LlmProvider, LlmRequestBuilder, PromptEngine, RemoteApi, TranslateMiddleware, LlmHttpClient (共享 HTTP 客户端, v0.29.2) |
| `cli/` | 9 | CliInterpreter, CommandRegistry, CommandExecutor, Pipeline, CommandSearch (BM25), CliAudit |
| `acp/` | 8 | AcpProtocol, AcpServer, AcpCrypto, AcpTransport, DelegateHandler, McpOverAcpBridge, ShareMemoryHandler |
| `session/` | 5 | SessionManager, History, Checkpoint |
| `security/` | 5 | Sanitizer, SecurityPolicy, PromptFirewall, IntegrityProvider |
| `evolution/` | 5 | EvolutionProvider (SPI), EvolutionExecutor, EvolutionGuide, EvolutionHook, EvolutionStore |
| `plugin/` | 4 | Plugin, PluginManager, PluginExecutor, PluginMarketplaceClient |
| `namespace/` | 3 | SelfExecutor, ScreenshotManager, NotifyBus |
| `mcp/` | 2 | McpServer, McpClient |
| `trigger/` | 1 | TriggerEngine |
| `mission/` | 1 | FleetMonitor |
| `error/` | 1 | ErrorCollector |
| `extension/` | 1 | ManifestParser |
| `spi/` | 1 | FrameworkAdapter (连接器 SPI, v0.23.0) |
| `ports/` | 1 | Ports (端口单一事实源, self.ports) |
| 根 | 4 | AgentEngine, DataPaths, KernelLog, KernelDispatchers |


### 3.2 mengpaw-core（Android 适配层，21 文件，下表为核心桥接）

| 文件 | 职责 |
|------|------|
| `security/Vault.kt` | API Key 加密存储 (EncryptedSharedPreferences + Android Keystore) |
| `security/IntegrityGuard.kt` | APK 签名校验，实现 `IntegrityProvider` 接口 |
| `security/StorageMonitor.kt` | 磁盘空间监控 (android.os.StatFs) |
| `namespace/SysExecutor.kt` | 系统信息命令 (39 个，反射 Android API) |
| `DataPathsInitializer.kt` | 桥接：`DataPaths.initialize(context.filesDir)` |
| `AndroidLogger.kt` | 桥接：`KernelLog.setLogger(AndroidLogger())` |

### 3.3 mengpaw-shell（主应用，65 文件）

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 入口 + 生命周期 + URL 处理 + 延迟初始化 (v0.29.1: 初始化拆至 AppInitializer, Compose 根拆至 AppRoot) |
| `AppInitializer.kt` | 关键路径初始化 (崩溃日志/DataPaths/插件管理器/SysExecutor/模板/日志器) |
| `ui/screens/AppRoot.kt` | Compose 根 — 主题装配 + MainScreen/设置页/插件市场全屏层 |
| `service/AgentRuntime.kt` | **NEW** UI/运行时分离 — 触发器桥接, 所有 IO 工作在此 |
| `ui/screens/` (46 文件) | MainScreen (头栏/侧栏/底表拆至 MainScreenHeader·MainScreenSidebars·MainScreenExpandSheet), SidebarContent (数据拆至 SidebarContentData, 孪生对话框拆至 sidebar-dialogs/TwinPairingDialogs), settings/ (5 文件: AgentSettingsContent 等), AgentViewModel, PluginViewModel, PluginMarketScreen, PluginDetailScreen, SettingsScreen, SettingsViewModel, BrowserScreen, HistorySidebar, SplashScreen |
| `ui/components/` (7 文件) | BigBangPopup, FleetMonitorOverlay, TokenChart, TokenStatsCollector, NotifyBanner 等 |
| `ui/AdaptiveLayout.kt` | WindowSizeClass 计算 |
| `ui/localization/Strings.kt` | 中英双语注解 |
| `service/` (7 文件) | ShellService, DreamWorker, EventReceiver, WakeReceiver 等 |


### 3.4 mengpaw-browser（独立浏览器，33 文件）

| 目录/文件 | 职责 |
|-----------|------|
| `BrowserActivity.kt` | 薄 Activity — 生命周期、MCP、返回键、onTrimMemory |
| `data/` (3 文件) | BrowserTypes, BrowserPrefs (含书签+会话持久化), HistoryStore |
| `service/GoogleTranslate.kt` | 免费翻译客户端 |
| `web/WebViewFactory.kt` | WebView 工厂 + App横幅CSS屏蔽 + onReceivedError |
| `util/` (3 文件) | AdBlocker, SmartNavigate, DownloadUtil |
| `BrowserDarkMode.kt` | 暗色模式 |
| `ui/` (15 文件) | 13 弹窗/条 (AgentSettings/Bookmark/FindBar/History/Icons/ImagePicker/MarkdownViewer/Password/ReaderMode/Settings/Tab/TopBar/Translate) + DesktopTabBar + NewTabPage |
| `ui/components/` (2 文件) | TabChip (标签样式), SearchEngineLogo (SVG) |
| `ui/theme/BrowserThemeConfig.kt` | Agent 主题配置 |
| `bridge/BrowserBridge.kt` | Java↔JS 双向桥 |
| `plugin/` (3 文件) | BuiltinBrowserPlugin, BrowserPlugin, BrowserPluginRegistry |
| `mcp/McpHttpServer.kt` | MCP HTTP 服务 |

**Markdown 文档打开 (v0.31.0+)**: 浏览器注册 `ACTION_VIEW` intent-filter 双轨——`file://` (文件管理器) 与 `content://` (FileProvider/SAF 选择) × `text/markdown` / `text/plain`+`*.md`。`BrowserActivity.checkMdFile` 冷启动与 `onNewIntent` 双路径取 md 内容 (≤500KB), 弹 `BrowserMarkdownViewerDialog`。Shell 提炼回传走独立私有 action `com.mengpaw.action.OPEN_MD` (extra `md`/`mdUri`, 见 BrowserReturnWatcher)。

**md 预览 WebView 化 (md-reader 观感)**: `BrowserMarkdownViewerDialog` 由 Compose MarkdownText 改为 WebView 渲染, **UI/动画/CSS 完全复刻 md-reader 扩展** (github.com/md-reader/md-reader, MIT)。管线: `web/MdViewerHtml.kt` (commonmark-java 0.24.0 显式依赖 + GFM 扩展, escapeHtml/sanitizeUrls 防注入) → HTML 注入 `assets/markdown_viewer/viewer.html` 模板 (占位 `<!--__MENGPAW_MD_BODY__-->`, 用注释标记避免花括号撞车) → `web/MdViewerWebView.kt` 轻量 WebView (不复用网页浏览工厂; `allowFileAccess=true` 为 API 30+ targetSdk 35 必需)。样式: 双主题 CSS 变量 (`@media (prefers-color-scheme)` 跟随系统; 亮 #607cd2/#2d3d50/AtomOneLight, 暗 #6785e0/#b5b5b8/#1d253d)、代码块 12px 圆角双层背景 + lang 标签 (hover 0.2s 淡出) + 复制按钮 (hover 淡入, .copied 1s 换 ✓, file:// 下 execCommand fallback)、h2 下边框、引用 4px 左边框 + info/tip/success/warning/danger 彩色圆角块、表格 max-content 横滚 + thead 条纹、图片点击放大模态 (backdrop blur 10px + transform 0.3s)、hljs v11 语法高亮 (assets 内嵌裁剪版: core + 19 常用语言, ~210KB)。细节: 对话框用 Dialog+Surface (AlertDialog text 槽无限高测量会压扁 WebView); HTML 后台线程构建; >1.2M 字符走 cacheDir 文件回退 (data: URL 有截断风险)。


### 3.5 插件模块（21 个，plugins/ 目录，按 settings.gradle.kts 为准）

> 插件数统一口径：**21 模块**（settings.gradle.kts；外部连接器已移至独立仓库 mengpaw-connectors，见下）| **14 内置**（BUILTIN_PLUGIN_IDS，含 v0.29.0 内置的 tavily；mengpaw-shell 打包）| **plugins.json 29 条目**（13 builtin + 14 remote + 2 embedded）

#### 基础功能 (6)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-fs | fs | cp, mv, stat, grep, glob (5) | ⭐ |
| plugin-net | net | curl, get, post (3) | ⭐ |
| plugin-skill | skill | ls, run, enable, disable (4) | ⭐ |
| plugin-clipboard | clipboard | copy, paste, clear (3) | ⭐ |
| plugin-framework | framework | discover, peers, trust, untrust, info, ping, connect, call, disconnect, adapters (10) | ⭐ |
| plugin-agent-tools | tools | import, ls, remove, search (4) | ⭐ |

#### AI / 搜索 (4)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-tavily | tavily | search, extract, setup (3) | ⭐ |
| plugin-render | render | models, generate, status, preview (4) | |
| plugin-comfy | comfy | nodes, workflow, run, preview, export (5) | |
| plugin-translate | translate | text, auto, langs, setup (4) | |

#### 多智能体 (1)

| 模块 | 命名空间 | 命令 |
|------|---------|------|
| plugin-hermes | hermes | team, discover, delegate, ask, memo, role (6) |

#### Agent 运行模式 (内置)

> Goal / Fleet (步坦协同) / 火种 (Swarm) 三种 Loop 模式已内置在 AgentEngine 中，不再作为独立插件。

| 模式 | 引擎方法 | 核心机制 |
|------|---------|---------|
| **Goal** | `AgentEngine.runWithGoal()` | GoalSession + 三层 Gate (GoalTurnGate/GoalBudgetGate/RubricGate) — LLM 自动评估完成度 |
| **Fleet (步坦协同模式, Combined Arms Mode)** | `AgentEngine.runWithFleet()` | 装甲集群推进+步兵协同清剿：多 Agent 编队协同，跨设备分布式执行复杂任务 (转发到火种模式，默认单模型，`roles` 为空) |
| **火种 (Swarm)** | `AgentEngine.runWithSwarm()` | 规划器拆解 → 并行 Worker（`roles` 按角色混合模型，零待命 Session）→ Verifier 验证 + Andon 决策 → 合成器。JIT 看板三闸门: `maxTotalSteps` 总预算 + `maxParallel` WIP + `maxStepsPerSubtask` 单任务。设计文档见 [docs/swarm-design.md](docs/swarm-design.md) |
| **Fleet+** | `runWithFleet()` + ACP | 步坦协同 + 跨 ACP 框架/设备协调 |

#### 浏览器扩展 (3)

| 模块 | 命名空间 | 命令 |
|------|---------|------|
| plugin-browser-push | browser.push | push, push.pending, push.accept, push.reject (4) |
| plugin-browser-search | search | extract, summary, engines, clean, md, outputs, clear (7) |
| plugin-browser-mcp | browser.mcp | tools, status, invoke (3) |

#### 工具链 (3)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-dev | dev.plugin | create, audit, share, examples, keywords, guide (6) | ⭐ |
| plugin-error-report | error | list, show, clear, export, status, upload (6) | |
| plugin-update | update | check, download, install, auto (4) | |

#### 系统权限 (1)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-root | root | status, exec, apps.*, fs.*, backup.* | ⭐ |

#### 记忆孪生 (1)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-memory-twin | twin | start, stop, status, peers, peer.info, peer.add, pair, unpair, sync, sync.auto, sync.qos, capabilities, delegate, route, lost, recover (16 条) | ⭐ |

#### 梦境模式 (1, 内置不可移除)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-dream | — (agent.dream 在内核) | — | ⭐ |

> **plugin-dream 是内置默认实现, 不能直接移除** (UNINSTALLABLE 白名单锁定)。作用: 把梦境实现显式注册为 DreamProvider SPI — 第三方插件可实现 `kernel.agent.DreamProvider` 接口, onInstall 注册自己的实现 (后注册者胜, 输入组装/LLM 提炼/文件整理可整体定制), 卸载后回退内核默认。

#### 智能体进化 (1, 内置不可移除)

| 模块 | 命名空间 | 命令 | 捆绑 |
|------|---------|------|:--:|
| plugin-evolution | — (evolution.* 在内核) | — | ⭐ |

> **plugin-evolution 是内置默认实现, 不能直接移除** (UNINSTALLABLE 白名单锁定)。作用: 把进化实现显式注册为 EvolutionProvider SPI — 第三方插件可实现 `kernel.evolution.EvolutionProvider` 接口, onInstall 注册自己的实现 (后注册者胜, 失败记录/省察引导/处置命令可整体定制), 卸载后回退内核默认。evolution.* 命令本身在内核命名空间注册 (PipelineManager)，与梦境模式 agent.dream 同模式。

> ⭐ = 捆绑在 Shell APK 中，随主应用安装，无需手动下载（12 个：framework/fs/net/skill/clipboard/dev/root/hermes(tribe)/memory-twin/agent-tools/dream/evolution；self 与 memory 已融入内核 agent.* 命名空间）
>
> plugin-hermes 模块实际实现为 `TribePlugin`（id=tribe-plugin，注册 tribe.* 22 条 + hermes.* 兼容命令），plugins.json 中对应 `tribe-plugin` 条目。

#### 外部连接器（已移至独立仓库 mengpaw-connectors）

> **v0.23.0 起**：5 个连接器模块（plugin-connector-common 共享库 + claude-code / reasonix / trae / qwenpaw / openclaw）已拆出至独立仓库
> **[mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)**（**MIT 许可，社区开放贡献**）。
> 本仓库不再包含连接器源码，仅经插件市场分发其 AAR（plugins.json status=remote, 用户手动 plugin.install）。
> 连接器构建依赖主仓库内核构件（JitPack: `com.github.WowBlueStudio.MengPaw:mengpaw-kernel:<tag>`，版本由该仓库 `kernelVersion` 统一控制）。

| 模块 | 框架类型 (--type) | 通道 | callTool 工具 | 上游 (许可) |
|------|------|------|------|------|
| plugin-connector-common | — (共享库) | jsch SSH + 交互式通道 + 配置原子存储 | — | jsch (MIT) |
| plugin-connector-claude-code | claude-code | SSH → `claude -p` | run, version | Anthropic Claude Code (闭源商业 CLI, 仅互操作调用) |
| plugin-connector-reasonix | reasonix | SSH → `reasonix run` | run, version | esengine/DeepSeek-Reasonix (MIT) |
| plugin-connector-trae | trea-ide | SSH → `trae-cli run` | run, show-config | bytedance/trae-agent (MIT) |
| plugin-connector-qwenpaw | qwenpaw | REST 8088 + SSH ACP | chat, acp-prompt | agentscope-ai/QwenPaw (Apache-2.0) |
| plugin-connector-openclaw | openclaw | WebSocket :18789 | — | — |

> 连接器实现内核 `spi.FrameworkAdapter` (frameworkName/connect/callTool/isOnline), onInstall 注册进
> FrameworkAdapterRegistry — plugin-framework 的 `framework.connect/call` 按通讯录类型自动分派。
> 使用链路: `framework.add <名称> <IP> [端口] --type <类型>` → `framework.connect <名称>` → `framework.call <名称> <工具> {"参数":"值"}`。
> 凭据经 `<ns>.config` 命令配置 (SSH 用户/密码或 PEM 密钥, 原子写入 {CONFIG}/)。默认通道 SSH
> (PC 需启用 Windows 自带 OpenSSH Server, 手机 → PC 零额外安装); QwenPaw 另支持 REST 直连。

### 3.5.1 记忆孪生架构 (plugin-memory-twin v0.22.0)

8 文件。基于 ACP 协议 + **工作区文件同步** (v0.22.0 起, 哈希链账本已移除) + 短码配对 + 心跳保活 + QoS 自适应。

**设计**: 孪生 = 同步整个 `{agent}/` 工作区文档, 保持跨设备一致。同步单元是文件而非账本条目 —— manifest 比对 + 差异传输 + LWW 冲突备份。同步范围: 根文档 (soul/profile/agents/boost/trigger/heartbeat.md/trumanshow.md/{date}_dream.md) + `memory/` 全部; **排除**: CLI.md (Android 操作指南)、inbox/ (本地任务队列)、dialog/ (本地对话流)、memory/backup/ (本机安全副本)。

#### 组件

| 文件 | 职责 |
|------|------|
| `MemoryTwinPlugin.kt` | 插件入口, 16 条 `twin.*` CLI 命令注册 |
| `TwinWorkspace.kt` | 同步范围/清单 (SHA-256 + mtime)/冲突落盘 (LWW + .conflict 备份) |
| `TwinSyncEngine.kt` | 同步流程 (WS_MANIFEST→WS_PULL) + 心跳保活 + QoS 自适应 |
| `TwinAcpHandler.kt` | `AcpHandler` 实现 — 处理 8 种孪生 ACP 消息类型 |
| `TwinDiscovery.kt` | Android NSD (mDNS) 局域网自动发现 |
| `TwinPairingEngine.kt` | 短码验证配对协议 (4 步: ANNOUNCE→CHALLENGE→VERIFY→CONFIRM) |
| `TwinCapability.kt` | `CapabilityCard` + `TwinCapabilityCollector` — 设备能力采集与协议版本协商 |
| `TwinRouter.kt` | 能力感知任务路由 |

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

#### 同步协议 (工作区文件同步, 请求-响应)

```
设备 A                          设备 B
  │  WS_MANIFEST (文件清单) ────→ │  比对本地工作区
  │ ←── {send: 对端缺的文件,     │  request: 本机缺的路径
  │      request: 本机缺的路径}  │
  │  WS_PULL (缺失路径列表) ───→ │  读取文件内容
  │ ←── {files: {relPath: 内容}} │
  │  → LWW 落盘 (冲突 → .conflict 备份 + 审计)
  │                               │
  │  [每 30s 双向 HEARTBEAT]      │  心跳保活, 90s 无响应→离线
```

> 历史缺陷修复 (v0.22.0): 旧账本链路因 `AcpTransport.send()` 丢弃 HTTP 响应体而**从未跑通** (LEDGER_BATCH 回不到请求方)。新增 `sendForResult()` 解析响应体, 工作区同步端到端可达。

#### QoS 自适应

| 网络类型 | 同步间隔 | 内容 |
|---------|:--:|------|
| WiFi / Ethernet | 60s | 全量工作区文档同步 |
| 移动网络 (非计费) | 300s | 全量工作区文档同步 (间隔更长) |
| 按流量计费 | 暂停 | 仅 `twin.sync` 手动触发 |

#### 发现机制 (双通道)

| 通道 | 方式 | 适用场景 |
|------|------|---------|
| mDNS 自动发现 | `TwinDiscovery` — `_mengpaw-twin._tcp` | 同 WiFi, 多播可达 |
| 手动 IP 添加 | `twin.peer.add <ip> [port] [name]` | 多播隔离, 跨网段, 不同频段 |

#### 核心 CLI 命令 (16 条)

```bash
# 生命周期
twin.start / twin.stop / twin.status

# 节点管理
twin.peers / twin.peer.info <id> / twin.peer.add <ip> [port] [name]

# 配对 (CLI 不可执行, 引导至 5 连击)
twin.pair / twin.unpair

# 同步 (工作区文件同步结果: 接收/发送/冲突数)
twin.sync [peer] / twin.sync.auto on|off / twin.sync.qos wifi|mobile|metered

# 能力与路由
twin.capabilities [--self|--all|<peer>] / twin.delegate <peer> <task> / twin.route <task>

# 设备丢失
twin.lost <peer> / twin.recover <peer>
```

> v0.22.0 移除: `twin.ledger.*` (6 条, 账本删除) / `twin.identity.*` (4 条, 身份文档随工作区自动同步) / `twin.dream.*` (2 条, 梦境产物 {date}_dream.md 随工作区同步传播)。

#### 系统提示词集成

中英文系统提示词含完整的记忆孪生使用指南: 功能概述 / 状态检查 / 节点发现 / 手动同步 / 任务委派 / 能力对比 / 路由推荐 / 配对方式 / 启动前提 / 解绑方式。

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

**结果纪律（v0.30.0+，系统提示词响应格式节）**: 提示词强制三条规则防 Observation 幻觉/谎报成功——① Action 发出后必须等框架返回 Result，后续思考只能引用 Result 原文，禁止自编结果；② Result 含 Error 时禁止声称成功，必须原样引用错误并如实汇报；③ install/rm/write 类写操作后必须用查询命令验证，验证失败 = 操作失败。Observation 由框架注入（`AgentEngine` 组装 `Command: …\nResult: …`），模型无自编通道；错误码随 Observation 以 `Error [CODE]: …` 形式可见（见 §5.2 错误码体系）。改提示词即改 `TEMPLATE_HASH` 自动失效缓存，无需手动 bump。

### 4.1.1 流式输出 (SSE + UI 播放器, v0.28.5 定型)

**链路**: `AdaptiveLlmProvider.consumeSseStream`(`bodyAsChannel()` + `readUTF8Line` 增量读, OpenAI/Anthropic 双格式解析)→ 引擎透传 onDelta → `AgentViewModel` UI 播放器 → 气泡渐进显示。

**网关行为(实测铁证)**: LLM 网关(如 DeepSeek)**不是逐 token 流** — 按 ~1s 批次批量 flush(~120 chunks/批); 相同 prompt 二次请求命中服务端 prompt cache 后整段回放(TTFB 8s+ 然后 ~200ms 全到)。突发到达是常态, 客户端改不了, 打字机观感必须由 UI 播放器兜底。

**UI 播放器**(`AgentViewModel.submitTask`, 核心设计):
- `onDelta` 只做 `synchronized(streamBuf) { streamBuf.append(delta) }` — 不直推
- **播放协程必须在 `Dispatchers.Default`**(关键坑): SSE 突发时数据全在内存缓冲, `readUTF8Line` 永不挂起 → 主线程被读取循环占死 → Main 调度的播放协程被饿死(实测 UI-PUSH 零输出)
- 节奏自适应: 每 50ms tick 消费 `ceil(剩余/50)` 字符 → 长文 ~2.5s 播完, 短文逐字
- 收尾: run() 返回后置 `streamFinished=true` → `join()` 等播放器播完 → cancel → 兜底 flush → final replace。join 防 Default 线程晚到 tick 覆盖最终消息
- 并发安全: streamBuf/streamPlayed/streamFinished 统一 `synchronized(streamBuf)` 监视器; `traces` 用 `Collections.synchronizedList`(播放协程 toList 与 onStep add 跨线程); 播放器体 try/catch 保证 join() 永不抛
- doTranslate(美系模型翻译)场景跳过 join/flush: 最终 replace 整段替换为中文, 英文逐字播放无意义

**显示策略**(`computeStreamDisplayText`): 含 `Final Answer:` 只显标记后; 含 `Action:`(工具轮)不显样板(onStep 重置"思考中..."); 含 `Thought:` 显其后; 无标记全文流式。

**工具调用提前通知 (v0.29.2, Reasonix ③ 对标)**: 流式中完整 `Action: <tool>` 行一落地即推送 `⚙ 正在执行 X…` 到运行中气泡(`ACTION_LINE_REGEX` 多行锚定, 行尾须完整 — 半截工具名不误报; "Action Input:" 天然不匹配), 不等工具执行完成(onStep)。消除工具轮流式空屏。UI 侧 `WaitingIndicator` 按前缀显示"正在执行 X… Ns"替代"思考中… Ns"。状态纪律: 检测在 `synchronized(streamBuf)` 内计数, `pushDisplay` 在锁外调用; `announcedTools` 随 onStep 清空。

**发送前路径延迟优化 (v0.28.6)**: 实测 4-13s 决策链中, 客户端规则/构造毫秒级, 主体是服务端 prefill TTFB。优化清单:
- "思考中..."气泡**前置**: 翻译/召回/引擎准备之前插入, 发送后 ~20ms 即有反馈(实测 T0→T1=23ms)
- 翻译与记忆召回 `async(Dispatchers.IO)` **并行**发起(关键词从原始 task 提取 — 中文词面语义更优); `detectCorrection` fire-and-forget 出 Main
- `saveCurrentSession` **异步化**: Main 只捕获快照(不可变 List 引用), 单线程 executor "session-save" 串行落盘, 在途快照合并(队列深度 ≤1), onCleared flush + awaitTermination(1s)
- 对话压缩后台化(见 4.3): 接近阈值提前在引擎自有 scope 预压缩, 请求不等待
- 等待期反馈: 思考中气泡附 spinner + 已等待秒数(`WaitingIndicator`, 流式文本到达后自动消失)
- 实测(模拟器): buildConversation 3ms; T0→S-OPEN 客户端侧仅 ~160ms; 剩余 ~9s 为服务端 TTFB(缓存未命中 prefill, 客户端不可优化)

### 4.1.2 HTTP 传输层 (v0.29.2, Reasonix ② 对标)

**共享客户端** `LlmHttpClient`(kernel/llm 单例): 此前每个 provider 各自 `new HttpClient(OkHttp)` — 会话/角色切换即重建连接池, 每次重新 TCP+TLS 握手(~2-4 RTT)。现在所有 provider(主 + fallback)复用同一连接池: `ConnectionPool(8, 5min)` + `retryOnConnectionFailure(true)` + connect 20s / read 120s + `pingInterval(60s)`(HTTP/2 主动探活, 半死连接 60s 内发现; HTTP/1.1 无副作用)。超时语义保留实证结论: OkHttp 引擎不映射 requestTimeoutMillis(死配置已删), 无 callTimeout(防误杀长流), readTimeout 是唯一活超时 — 120s 为静默判定阈值(对齐 Reasonix idle watchdog), 思考期 60s+ 无数据仍留 ~60s 余量。`close()` 为 no-op(进程级共享)。

**网络状况门卫** `NetworkConditionGate`(kernel SPI) + `NetworkConditionMonitor`(shell 实现, v0.29.2): Android 系统网络状态注入内核重试策略 — `registerDefaultNetworkCallback`(onAvailable/onLost → isOnline) + `NET_CAPABILITY_VALIDATED`/下行带宽(→ quality 0/1/2)。两条策略: 断网 → 重试立即失败快返(不烧 6 次退避 + fallback 链, 错误气泡直出); 弱网 → 退避 ×3(差)/×1.5(中)。免危险权限(仅 ACCESS_NETWORK_STATE, manifest 已有); 真实蜂窝 dBm 需 READ_PHONE_STATE 运行时弹窗, 刻意不用。注入点: MainActivity.onCreate attach; provider 构造传 `networkGate = NetworkConditionMonitor`(7 处: AppRoot×2 / AgentSessionFactory×3 / DreamWorker×1 / 角色缓存)。

**前缀形状监测** `SystemPromptShape`(LlmRequestBuilder.kt, Reasonix cache_shape.go 对标): 每轮请求 wire 上 system prompt 做 SHA-256, 形状变化即 `W/CacheShape` 告警("cache prefix changed…自动前缀缓存将短暂失效")。调用点: 两个 provider 的 `buildRequestBody`(首条消息 role=system)。与 PromptEngine mtime 指纹缓存互补: 前者保证组装稳定, 后者实测 wire 形状。

**Fallback 缓存统计直通**: RemoteApi 补齐 `lastUsage`(流式内联 usage + 非流式 parseResponse); `callWithRetryAndFallback` 在 fallback 服务成功后把 usage 透传给主 provider — 壳层 `session.provider.lastUsage` 不再恒 null, fallback 调用计入 TokenStatsCollector 缓存命中统计。

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

`SessionManager.compressIfNeeded()` — 消息数超过 50 条时，调用 LLM 将旧消息压缩为 system summary，保留最近问答组(保底组数 + token 预算档位 8%/15%/25%)，原始消息归档 `dialog/YYYY-MM-DD.jsonl`，上限 200 条。

**v0.28.6 后台预压缩**: 压缩移出首请求关键路径 —
- `scheduleCompressionIfNeeded`(消息 ≥ threshold-8 即 42 条时在引擎自有 `compressionScope` 后台启动; `ConcurrentHashMap` 单在途去重)
- `awaitCompressionIfNeeded`(在途则不阻塞放行 — 快照一致, 压缩与请求并发无害; 无在途且仍超阈值才同步兜底)
- `compressionScope` 独立于 runningJob(随引擎生灭), **刻意不在 stop() 取消** — submitTask 每轮先 stop, 取消会杀死在途压缩(浪费一次 LLM 调用 + 历史压不下去)
- 并发契约: 消息列表替换在 `synchronized(this)` 监视器内(与 addMessage/recordInterruptedTurn 共用); LLM 调用窗口内新增消息用**身份 diff**(`IdentityHashMap`)保留 — 200 条上限的 removeAt(0) 会破坏旧 `afterSnap.drop()` 下标对齐逻辑

### 4.4 翻译中间件

美国模型 (OpenAI/Grok/Claude) 自动中→英→模型→英→中流水线，为中文用户节省约 40% token 消耗。

**v0.28.6 改为 opt-in**: 默认关闭(`TranslateMiddleware.enabled = false`)，仅用户主动开启(设置页 Agent → "自动翻译(美系模型)"开关, 持久化 `DataPaths.CONFIG/auto_translate`)时才加载 Google 翻译 — 不开启时零 translate.googleapis.com 请求。开启后仍只对美系模型生效; 发送链中翻译与记忆召回 `async` 并行发起。

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

Agent 通过内核命令按需加载文档：
- `agent.cli` — CLI 完整参考 (含 browser-tools 段)
- `agent.docs` — 列出所有 Agent 文档
- `skill.ls` + `skill.run <name>` — 先索引再加载具体 Skill

### 4.7 MCP 协议：通用设备语言

MCP 在 MengPaw 中的定位不是"让 AI 调用工具的协议"，而是**让任何碎片设备加入 Agent 网格的通用语言**。

#### 协议的本质

MCP 协议极其简单——JSON-RPC + 三个原语（tool / resource / prompt）。好的协议都是极简的，HTTP 几个动词统治了互联网三十年。MCP 的三个原语足够让任何设备在 Agent 网格中自描述和互操作。

#### 双轨通信 (v0.22.1+)

| 轨道 | 通道 | 说明 |
|---|---|---|
| 本机轨 | 标准 MCP `127.0.0.1:9881` (plugin-framework 网关) | 任何 MCP 客户端直连, tools/call 真实执行插件命令 |
| 远程轨 | MCP over ACP `:9876` (配对+加密) | 跨设备调用, requestId 请求-响应一轮完成 |
| 框架轨 | 连接器插件 (内核 FrameworkAdapter SPI) | 非 MengPaw 框架 (OpenClaw WS / QwenPaw REST) |

协议分层: 内核 = 协议核心 (ACP + MCP + SPI, 无具体框架); plugin-framework = 内置协议插件 (发现/信任/网关/分派); connector-* = 外部分发连接器（独立仓库 [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)，MIT）。**接入指南见 [PROTOCOL.md](PROTOCOL.md)**。


### 4.8 记忆三轨制 (v0.15.0+, 单轨 v0.22.0)

MengPaw 使用三层记忆架构 (单轨, v0.22.0 起)。`{agent}/memory/` 目录持有全部记忆——旁轨 `memory.md` 轨道已删除，任务记忆 (自动任务记录) 并入三轨中期。会话不是记忆形式——会话中的细节保留在按日分片的中期记忆中，由梦境模式按日压缩提炼。

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
- 梦境模式 (`agent.dream`) 桥接中期→长期: 分析今日中期记忆, 产出结构化洞察——管道 (v0.22.0): 读全部中期分片 → 复制 `memory/backup/` → 提炼 `{agent}/{date}_dream.md` (同日多次追加) → 删除已整理分片 → 30 天前备份自动清理

**Notes 笔记目录 (v0.30.0+)**: `{agent}/Notes/` 存放记忆之外的笔记——如其他 Agent 发来的知识信息。`AgentDocs.bootstrap` 预建目录, 设置页工作区文件树在 memory 节点下方固定显示 Notes 节点 (仅收 .md 子行), Agent 通过工作区文件命令 (`agent.write/read/ls/rm`) 读写, 不注入系统提示词。设计意图: 记忆 (memory/) 是需提炼保真的结构化知识, Notes 是低约束随手笔记区。

**工作区文档重置 (v0.30.0+)**: 设置页工作区文件树中, 8 份预置文档 (agents.md / heartbeat.md / modes.md / profile.md / soul.md / trigger.md / trumanshow.md / memory/memory.md) 的按钮为「重置」——`AgentDocs.resetDoc` 从 APK 模板 (`{BASE}/agent-templates/{lang}/`, 缺失回退 zh) 原子覆盖写回预置版; 名单外文档 (中期/项目记忆、梦境文档 {date}_dream.md、boost.md 等) 保持可删除。

**工作区文档编辑 (v0.31.0+)**: 所有 md 文档行均有「编辑」按钮——`FileProvider` (file_paths.xml 已映射 `Agent文档/`) 共享 content URI + `ACTION_VIEW` (优先 `text/markdown`, 无处理器回退 `text/plain`; 两者皆无 Toast 提示), 经系统选择器交给其他软件打开 (MP 浏览器也在候选之列, 选中即由浏览器渲染)。目录节点 (memory/Notes) 无按钮。

**MarkdownText 截断语义 (v0.31.0+)**: `parseMarkdown` (design-system) 修复「内容掉出代码块」根因——旧实现 100KB 预截断在任意字符边界硬切, 切点落在 ``` 围栏内时闭合丢失, 后续整段被解析成巨型代码块。现改为**完整解析 + 块边界预算截断**: fence 在解析期必然闭合, 每个渲染的块永远完整; 超过 100K 字符预算 (按块渲染输出量度) 时在块边界停止并追加「…(内容过长，已截断)」提示块; 单块超预算整体跳过; 500 节点上限保留为防御。聊天气泡/设置页共享组件同时受益。

---

## 5. CLI 规范

### 5.1 内置命名空间（kernel）

#### self — Agent 自我管理 (16)
`status` | `config [key=value]` | `stats` | `version` | `avatar` | `theme` | `mcp` | `trigger` | `acp` | `tools [namespace]` | `ports [--json]` | `search <query> [--top N]` | `search.stats` | `time [format]` | `notify.message <text>` | `notify.banner <text> [--level]`

#### evolution — 进化系统 (5, 内核注册, 提供者由同捆插件 plugin-evolution 提供)
`audit` | `report <描述>` | `learn.command <命令> <描述> [--keywords 词,词]` | `reactions` | `mark-corrected <id>`
> 失败钩子归系统 (ErrorCollector.onReport): 命令失败/循环/崩溃自动写入失败模式库 (`{AGENTS}/{agent}/evolution/failures.jsonl`), 下次 LLM 调用注入金字塔省察引导 (L1 事实→L2 归因→L3 用户视角→L4 进化)。用户纠正 (shell 层识别) 写入用户反应档案 `reactions.md`。处置: 指令错→`learn.command`/`self.search`, 常识错→`agent.memory.keep`, 行为错→`agent.write soul.md`, 框架错→`report`。实现经 EvolutionProvider SPI 可替换 (同捆插件 plugin-evolution 注册内核默认, 第三方覆盖后卸载回退)。


#### agent — 文档 + 内存 + 工作区 (27+)
**文档 (3)**：`docs` | `cli` | `profile` | `soul` | `boost` | `boost.delete`

**记忆三轨 (18)**：`memory` (看长期) | `memory.keep <内容>` (写长期) | `memory.write <id> <内容>` (指定 ID 写/更新) | `memory.read <id>` (按 ID 读单条) | `memory.search <关键词> [--track long|mid|project]` (跨轨搜索) | `memory.stats` (统计) | `memory.rm <时间戳>` | `memory.edit <时间戳> <内容>` | `memory.mid [日期]` (看中期) | `memory.record <内容>` (写中期) | `memory.mid.delete <日期>` | `memory.mid.rm <日期> <时间戳>` | `memory.mid.edit <日期> <时间戳> <内容>` | `memory.project [名称]` (看项目) | `memory.project.save <名称> <内容>` | `memory.project.rm <名称> <时间戳>` | `memory.project.edit <名称> <时间戳> <内容>` | `memory.project.delete <名称>`

**其他 (5+)**：`audit` | `browser-tools` | `dream` | `cleanup` | `storage`

**会话 (4)**：`sessions [keyword] [limit]` | `session.delete <id>` | `session.archive <id>` | `session.current`

**工作区文件 (6)**：`read <path>` | `write <path> <content>` | `ls [path]` | `rm <path>` | `mkdir <path>` | `output`


#### plugin — 插件管理 (12 + 5)
**内核 (12)**：`marketplace [--refresh]` | `search <query>` | `install <id>` | `uninstall <id>` | `list [--ports]` | `info <id>` | `enable <id>` | `disable <id>` | `update <id>` | `upgrade --all` | `auto <wake\|sleep\|status\|sleep-idle>` | `verify <id>`

**dev 插件扩展 (6)**：`create --type script|native --name <name> [--author <作者>] [--desc <描述>]` | `audit --target <id>` | `share --plugin <id> --to <target>` | `examples` | `keywords --target <id>` | `guide`
> dev 插件的命令实际注册为 `dev.plugin.create` / `dev.plugin.audit` / `dev.plugin.share` / `dev.plugin.examples` / `dev.plugin.keywords` / `dev.plugin.guide`，因为 PluginManager 根据插件 ID (`dev-plugin`) 自动派生命名空间 `dev`。`plugin.create` 在 CLI 文档中出现时均指 `dev.plugin.create`。`dev.plugin.guide` 输出能力边界文档并落盘 `插件文档/plugin-dev-guide.md` 供用户阅读。

#### sys — Android 系统 (40 命令，通过 Android 适配层注入)

**设备信息 (1)**: `device` (型号/厂商/SDK/架构)

**电源 (4)**: `battery` | `power` | `power.save` | `screen.on`

**网络 (4)**: `network` | `wifi` | `wifi.enable` | `bluetooth`

**定位 (1)**: `location` (需权限)

**硬件 (4)**: `cpu` | `memory` | `storage` | `sensors`

**屏幕 (3)**: `display` | `screen.brightness <0-255>` | `screen.off`

**音量 (2)**: `volume` | `volume.set <type> <level>`

**相机 (1)**: `camera` (需权限)

**应用 (5)**: `apps` (需权限) | `app.launch <pkg>` | `app.uninstall <pkg>` | `app.info <pkg>` | `browser.open [url]` (前台唤醒 MP 浏览器, 带 url 同时打开)

**剪贴板 (2)**: `clipboard` | `clipboard.set <text>`

**Intent (3)**: `intent.open <url\|pkg>` | `intent.share <text>` | `intent.view <file>`

**通知 (3)**: `notification.id` | `notification.send <title> <text>` | `notification.cancel <id>`

**权限 (3)**: `permission.list` | `permission.request <name>` | `permission.check <name>`

**其他 (4)**: `telephony` | `vibrate [ms]` | `ringtone.play` | `alarm.set <seconds> <msg>`

### 5.2 插件命名空间

格式 `namespace.command arg1 arg2 "arg with spaces" --flag value`

**参数格式（v0.30.0+ 门卫）**: Action Input 一律 CLI 纯文本，多个参数空格分隔，**禁止 JSON**。PromptEngine 的 tolerant JSON 解析对 `{` 开头参数会丢弃 key 只取值——单 key 碰巧兼容，多 key 会参数错位；JSON 解析失败则整个串当参数。AgentEngine 组装命令行前设门卫：raw 键以 `{` 开头 或 JSON 多值（>1 key）→ 返回 `PARAM_FORMAT_ERROR`，不执行。

**错误码体系** (`ErrorCodes`, 随 Observation 注入，模型可见 `Error [CODE]: ...`)：

| 错误码 | 含义 |
|--------|------|
| `ERR_INVALID_INPUT` | 参数缺失/用法错误 |
| `PARAM_FORMAT_ERROR` | 参数格式与命令签名不匹配（如 JSON 当 CLI 传） |
| `ERR_NOT_FOUND` | 命令/插件/文件不存在 |
| `ERR_PERMISSION_DENIED` | 安全策略/权限拦截 |
| `DOWNLOAD_FAILED` | 插件下载 HTTP 失败（404/5xx，`MarketplaceDownloadException`） |
| `NETWORK_OFFLINE` | 网络不可达（`MarketplaceNetworkException`，双源 + ghproxy 全失败） |
| `ERR_TIMEOUT` / `ERR_IO` / `ERR_INTERNAL` | 超时 / IO 错误 / 未归类内部错误 |

#### fs — 文件系统 (5)
`cp <src> <dst>` | `mv <src> <dst>` | `stat <path>` | `grep <pattern> [path] [--regex] [-i] [--context N]` | `glob <pattern> [path]`
> 读/写/列/删/建目录用内核 `agent.read/write/ls/rm/mkdir`(工作区文件命令, 与 fs 沙箱同界)


#### net — 网络 (3)
`curl <url>` | `get <url>` | `post <url> <body>`

#### skill — 技能 (7)
`ls` | `run <name>` | `enable <name>` | `disable <name>` | `info <name>` | `search <query>` | `create <name> <content>`

#### tools — 命令集注册 (4) (Agent Tools)
`import <名称> <url|json>` | `ls` | `remove <名称>` | `search <关键词>`

> Agent 导入外部 CLI 命令集（GitHub CLI / 飞书 CLI 等），注册 per-agent 索引。命令集 JSON 清单存 `Agent文档/{agent}/tools/{name}.json`，导入后紧凑摘要注入系统提示词（Agent 每次对话直接可见，无需遍历完整命令文档）。
>
> ```json
> { "name": "gh", "displayName": "GitHub CLI", "source": "<url>",
>   "commands": [{ "name": "gh pr list", "description": "...", "usage": "gh pr list [--state open]" }] }
> ```


#### clipboard — 剪贴板 (3)
`copy <text>` | `paste` | `clear`

#### tavily — AI 搜索 (3)（内置）
`search <query> [--max=N]` | `extract <url>` | `setup <key>`（配置/查看 API Key，存 `{BASE}/配置/tavily.json`，env `TAVILY_API_KEY` 优先）
> v0.28.7 起内置：随 APK 自动安装激活，Agent 原生即有搜索能力（FewShot 示例 2 演示直接使用）

#### hermes — 多智能体 (6)
`team` | `discover` | `delegate <agent> <task>` | `ask <agent> <question>` | `memo <content>` | `role <agent> <role>`

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


### 6.2 Vault

`EncryptedSharedPreferences` + Android Keystore (`security-crypto:1.1.0-alpha06`)。文件级加密 + 应用层加密双层保护。`allowBackup=false` 防止备份泄露。

**容错机制**: 若 Keystore 不可用（部分 OEM 设备已知问题），重试一次后降级到 `InMemoryPreferences`——绝不以明文落盘。`isAvailable` 字段让调用方判断加密是否正常。


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
- **工作区防盗**: 未配对设备无法访问 `WS_MANIFEST/WS_PULL` (AcpServer 鉴权)
- **哈希校验**: manifest 每文件 SHA-256, 哈希不同的文件才传输
- **原子写入**: 所有同步文件使用 `tmp → rename` 原子写入, 防崩溃损坏
- **冲突保护**: 本地较新且内容不同 → `.conflict` 备份 + 审计 + inbox 提示, 不覆盖

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
| **NATIVE** | 中 | 编译型（产物 JAR/AAR），Kotlin 逻辑、有状态，需编译 |
| **SCRIPT** | 低 | JSON 声明即用，Agent 可零代码自建 |

> JAR/AAR 统一归为 NATIVE（内核 `PluginType` 权威枚举只有 `{NATIVE, SCRIPT}`）。

### 7.3 市场发布

客户端直读 `plugins.json`（raw.githubusercontent.com 全球 / gitee.com/raw 国内，GeoRouter 选择），ETag 缓存 (5 分钟)，SHA256 校验。**发布即生效**——push 到 GitHub master 后 GitHub 侧立即可用；Gitee 侧依赖镜像同步（`.github/workflows/gitee-sync.yml`，需配置 **GITEE_SSH_KEY** secret——Gitee 私人令牌只能调 API 不能做 git 认证；SSH 私钥经 GitHub secret 传递会损坏，须 base64 单行存储）。**v0.29.0 起镜像已全量建成**：插件 AAR 发布在独立仓库 mengpaw-connectors（GitHub + Gitee 双 release，tag `plugins-vX.Y.Z`），国内客户端 GeoRouter 首选 Gitee 不再依赖 GitHub fallback。

信任链：官方 → 信任框架 (SHA256 + 确认) → 公网 (SHA256 + 确认 + 来源标记) → 未验证 (拒绝)

仓库工具链（见 [PLUGIN_DEV_GUIDE.md](PLUGIN_DEV_GUIDE.md) §5.3）：
- `scripts/build-plugins.ps1` — 批量构建插件 AAR，自动回写 plugins.json 的 checksum/size/changelog（remote 条目不动，含连接器条目）
- `scripts/validate-plugins.ps1` — 校验 plugins.json（字段/命名空间/checksum 与 AAR 一致性）
- 插件 AAR 发布用独立 tag `plugins-vX.Y.Z`；`.claude/skills/plugin-dev.md` 为插件开发/发布 skill

### 7.4 开发流程

`dev.plugin.create --type script|native` → `dev.plugin.audit --target <id>` → `dev.plugin.keywords --target <id>` → `dev.plugin.share --plugin <id> --to <框架>`，通过 dev-plugin（捆绑在 Shell 中）即可完成。

详细指南见 [PLUGIN_DEV_GUIDE.md](PLUGIN_DEV_GUIDE.md)。

## 9. 构建与部署

### 9.1 环境要求

- Android SDK 35 + JDK 17 + Gradle 8.12
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

### 9.2 插件构建工具链

- `scripts/build-plugins.ps1` — 模块列表动态派生自 settings.gradle.kts（26 模块），逐模块 assembleRelease，产物复制到 `releases/plugins/plugin-<name>-<version>-release.aar`，自动回写 plugins.json 的 checksum/size/changelog（remote 条目）
- `scripts/update-plugins-json.py` — JSON 写回（规避 PowerShell 5.1 的 ConvertTo-Json 中文转义缺陷）
- `scripts/validate-plugins.ps1` — 只读校验：结构/id 唯一/字段完整/SemVer/URL 与 checksum 一致性/与代码交叉校验（namespaceFor 派生规则、shell 捆绑 vs plugins.json builtin 对应）
- 插件 AAR 发布 tag：`plugins-vX.Y.Z`（独立于版本 tag `vX.Y.Z`）

---

## 10. 初始化流程


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

**设计原则**: Agent 不自动启动。安装→配置→用户驱动。


---

## 11. 许可证与商业 (v0.23.0+)

### 11.1 双许可模型

MengPaw 以**双许可**发布（详细条款见 [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)）：

| | 社区版 | 商业版 |
|---|--------|--------|
| 许可 | AGPL-3.0（[LICENSE](LICENSE)） | 商业授权（[COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)） |
| 费用 | 免费 | 付费（协商） |
| 适用 | 个人/开源/遵守 AGPL 义务的部署 | 闭源分发/白标/嵌入产品/不想公开修改源码的服务化部署 |

- **免费边界**：企业内部自用且不对外分发不受限；遵守 AGPL（公开修改源码）的任何分发与部署免费
- **商用边界**：闭源分发、白标、嵌入产品、服务化部署不公开源码 → 需购买商业授权
- 既有版本（≤v0.23.0）的 AGPL 授权继续有效；双许可自 v0.23.0+ 适用
- 公司为唯一版权人（主仓库暂不接受外部代码贡献），双许可无版权障碍

### 11.2 SPDX 头规范（新格式）

所有 `.kt`/`.kts` 文件首两行：

```kotlin
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
```

`LicenseRef-Commercial` 指 [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md) 定义的商业授权，使用者二选一。

### 11.3 贡献政策

- **主仓库**：仅接受 Bug 报告与功能请求（GitHub Issues 模板）；**暂不接受 PR**（保证版权单一归属），未来可能开放
- **连接器仓库**（[mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)）：MIT 许可，**社区开放 PR**（inbound=outbound，无需 CLA）
- **商用咨询**：1138018324@qq.com

### 11.4 连接器依赖内核的许可边界

连接器（MIT）编译期依赖内核构件（AGPL，JitPack），仅实现 `spi.FrameworkAdapter` / `Plugin` 接口——独立作品，非 AGPL 内核的衍生作品。**铁律：连接器不得复制内核源码，只依赖构件**；若未来需要复用内核代码，须将该部分留在主仓库并遵守双许可。
