# MengPaw 开发文档

> 📄 灵感来源: [ATTRIBUTIONS.md](ATTRIBUTIONS.md) — QwenPaw · Hermes · OpenClaw · Claude Code · ReAct · ComfyUI · LangChain · CrewAI · Dify · Tavily · Arco Design · Material Design 3

> **版本**: 0.4.0 | **更新**: 2026-07-21 | **定位**: Android Agent 框架 — 26 插件生态 + 浏览器操控 + 多模态 + 3 模块(Shell/Browser/TV) + 12 LLM Provider + R8混淆 + 安全加固 + 模拟器验证通过

---

## 1. 项目概述

MengPaw（檬爪）— 微内核+插件架构的 Android Agent 框架。核心理念：**Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

| 特征 | 说明 |
|------|------|
| 微内核 | 基础 APK 仅含 Chat UI + CLI 引擎 + LLM + 2 个内置命名空间(self/plugin)，其余 9 个命名空间按需下载 |
| 插件化 | 所有功能通过 Plugin 接口动态加载，Agent 可自主安装/卸载/创建/发布插件 |
| 零 Python | 纯 Kotlin，无 Python 运行时 |
| 多通道 | AIDL（系统集成）/ Unix Socket（Termux）/ HTTP（调试） |
| 独立浏览器 | 独立浏览器 APK，Intent 互通，支持完整导航 |
| 多模型 | OpenAI / DeepSeek / Kimi / GLM / Qwen / 自定义，支持重试+降级链 |
| 插件市场 | GitHub Pages 托管的静态 JSON 索引，ETag 缓存，SHA256 校验 |
| Agent 自我升级 | plugin.search → plugin.install → 命令即可用；Agent 可创建 SCRIPT 类型插件 |

适用场景：自动化研究、RPA 替代、数字助手、渗透测试、边缘 AI。

---

## 2. 架构设计

### 2.1 整体架构

微内核+插件三层结构：

- **MengPaw-Shell (基础 APK)**: Chat UI + Settings(API Key)，仅依赖微内核 AAR
- **MengPaw-Core (微内核 AAR, ~15 源文件)**: CLI 引擎、安全、会话、LLM、插件框架、AgentEngine
  - 内置命名空间: `self`(Agent 自省) + `plugin`(插件管理，10 命令)
- **插件层 (9 个独立模块, plugins/)**: fs / net / memory / skill / self-ext / ui / proc / clipboard / notification / browser(独立 APK)
- **外部接口层**: AIDL / Unix Socket / HTTP
- **插件市场**: GitHub Pages 托管 `plugins.json`，ETag 缓存，Agent 通过 `plugin.*` 命令浏览/安装

- **MengPaw-Shell (主 APK, 21MB Debug / 6.8MB Release)**: 内含 LLM Core 和 MengPaw-Core 微内核
  - 微内核：CLI 引擎、安全沙箱、会话管理、9 个内置命名空间
- **扩展 APK 层**: MengPaw-Browser（独立 APK, 8.4MB）/ Device（规划中）/ Code（规划中）
- **外部接口层**: AIDL / Unix Socket / HTTP 三种通道暴露 `mp` 命令

### 2.2 响应式布局

基于 Material 3 `WindowSizeClass` 自适应屏幕尺寸:

| 宽度 | 导航 | 插件 | 聊天 |
|------|------|------|------|
| Compact (<600dp) | `NavigationBar` 底部 | 单页全屏 | 全宽 |
| Medium (600-840dp) | `NavigationRail` 左侧 | 列表-详情双栏 | 居中 720dp |
| Expanded (>840dp) | `NavigationRail` | 双栏 + 详情 | 居中 720dp |

深色主题通过 `ThemeColors` 动态存取器适配 — 所有 Screen 从 `MaterialTheme.colorScheme` 读取颜色，切换即时生效。

### 2.3 跨 APK 通信

- Shell (com.mengpaw.shell) ↔ Browser (com.mengpaw.browser)，通过 Intent 互相唤醒
- 双方启动时检测对方安装状态，未安装时静默回退
- Shell 未装 Browser → 回退内置 WebView (BrowserScreen)
- Browser 未检测到 Shell → 隐藏唤醒按钮

### 2.4 数据流

用户输入 → LLM Core 生成 CLI 命令 → Pipeline（解析→安全→执行）→ 内置/扩展命名空间 → 传输层 → 结果返回 LLM → 循环至 Final Answer 或达上限

AgentEngine 支持两种执行模式：
- **ReAct 循环**: Thought → Action → Observation 标准模式，含循环检测（同一命令连续 3 次中止）和最大步数限制（默认 50）
- **Plan-Execute**: `runWithPlan()` — LLM 先将任务分解为 3-7 步计划，再逐步执行，每步有独立 mini ReAct 循环

---

## 3. 核心概念

### 3.1 LLM 调用链

AdaptiveLlmProvider 提供统一的 LLM 调用接口，特性：
- **Provider 自动检测**: 根据 endpoint URL 识别服务商（openai/deepseek/kimi/glm/qwen）
- **认证适配**: GLM 使用裸 API Key，其他使用 Bearer Token
- **指数退避重试**: 默认 2 次重试，延迟 500ms×(1,2,4...)
- **Fallback 降级链**: primary → fallback[0] → fallback[1] → ...，全部耗尽抛 LlmFallbackExhaustedException
- **响应格式归一化**: 兼容 OpenAI choices[0].message.content 和 GLM data[0].content

### 3.2 DeepSeek Prefix Cache 优化

LlmRequestBuilder 确保 messages[0]（system prompt）在所有请求中字节级相同，命中 DeepSeek KV-Cache：
- Cache miss: $0.14/1K tokens
- Cache hit: $0.0028/1K tokens（50 倍差价）
- 跟踪 cumulativeCacheHitTokens / cumulativeCacheMissTokens

### 3.3 对话压缩

SessionManager.compressIfNeeded() — 消息数超过 50 条时，调用 LLM 将旧消息压缩为一条 system summary，保留最近 10 条完整上下文。历史上限 200 条。

### 3.4 命名空间（内置 4 + 插件 9 = 13 命名空间，~60 命令）

**内置命名空间（始终可用，在 core 中）:**

| 命名空间 | 职责 | 命令数 | 示例 |
|---------|------|--------|------|
| `self` | Agent 自省 | 4 | `self status` |
| `plugin` | 插件管理 | 10 | `plugin.install fs-plugin` |
| `agent` | Agent 文档 | 6 | `agent.memory` |
| `sys` | 系统信息 | 11 | `sys.battery`, `sys.network` |

**插件命名空间（按需安装）:**

| 命名空间 | 插件 ID | 命令数 | 含权限 |
|---------|---------|--------|--------|
| `fs` | fs-plugin | 8 | — |
| `net` | net-plugin | 3 | INTERNET |
| `memory` | memory-plugin | 6 | — |
| `skill` | skill-plugin | 4 | — |
| `ui` | ui-plugin | 7 | BIND_ACCESSIBILITY_SERVICE |
| `proc` | proc-plugin | 3 | — |
| `clipboard` | clipboard-plugin | 3 | — |
| `notification` | notification-plugin | 3 | POST_NOTIFICATIONS |
| `vision` | vision-plugin | 3 | CAMERA (按需) |
| `audio` | audio-plugin | 4 | RECORD_AUDIO (按需) |

**权限策略**: sys/vision/audio 等命令使用按需权限——调用时检查，未授权返回提示而非崩溃。不在应用启动时弹出权限对话框。

clipboard 通过 java.awt.Toolkit 反射实现（Android/Desktop 兼容），notification 为 stub（需 NotificationListenerService）。

### 3.5 安全禁区

API Key 是唯一禁区：
- 存储：SharedPreferences 沙箱隔离（`/data/data/com.mengpaw/shared_prefs/mengpaw_vault.xml`）
- ✅ LLM Core 初始化时读取
- ❌ CLI 命令无法访问
- ❌ 日志自动脱敏（Sanitizer：支持 OpenAI/Anthropic/Google Key、Bearer Token、Base64 启发式）
- ❌ 扩展无法读取

---

## 4. 模块体系

### 4.1 模块清单

**核心模块 (4):**

| 模块 | 类型 | namespace | 状态 | 源文件数 |
|------|------|-----------|------|----------|
| mengpaw-core | Library AAR | com.mengpaw.core | ✅ | ~15 (微内核) |
| mengpaw-shell | APK | com.mengpaw.shell | ✅ | 13 |
| mengpaw-design-system | Library AAR | com.mengpaw.design | ✅ | 4 |
| mengpaw-browser | APK (独立) | com.mengpaw.browser | ✅ | 1 |

**插件模块 (21):**

| 模块 | 插件 ID | 命令数 | 依赖 | 状态 |
|------|---------|--------|------|------|
| plugin-fs | fs-plugin | 8 | 纯 JDK | ✅ |
| plugin-net | net-plugin | 3 | ktor-client | ✅ |
| plugin-memory | memory-plugin | 6 | 无 | ✅ |
| plugin-skill | skill-plugin | 4 | 无 | ✅ |
| plugin-self | self-plugin | 4 | 纯 JDK | ✅ |
| plugin-ui | ui-plugin | 7 | coroutines | ✅ |
| plugin-proc | proc-plugin | 3 | 无 | ✅ |
| plugin-clipboard | clipboard-plugin | 3 | 无 | ✅ |
| plugin-notification | notification-plugin | 3 | 无 | ✅ |

**规划中 (2):**

| 模块 | 说明 | 状态 |
|------|------|------|
| mengpaw-device | 无障碍服务扩展 | ⏳ |
| mengpaw-code | JS/Python 沙箱扩展 | ⏳ |

### 4.2 构建配置

| 配置项 | Shell | Browser | Core | Design |
|--------|-------|---------|------|--------|
| compileSdk | 35 | 35 | 35 | 35 |
| minSdk | 26 | 26 | 26 | 26 |
| targetSdk | 35 | 35 | — | — |
| versionName | 0.1.0-alpha | 1.0.0 | — | — |
| versionCode | 1 | 1 | — | — |
| R8 | Release 启用+资源压缩 | Release 启用+资源压缩 | Release 启用 | — |

Shell 权限：INTERNET, FOREGROUND_SERVICE, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

Browser 权限：INTERNET, ACCESS_NETWORK_STATE

### 4.3 依赖关系

```
mengpaw-shell (基础 Chat APK)
    ├── mengpaw-core (微内核 AAR) — CLI引擎、安全、会话、LLM、插件框架
    │       ├── kotlinx-coroutines 1.9.0
    │       ├── kotlinx-serialization-json 1.7.3
    │       └── ktor-client (core+okhttp) 3.0.3
    ├── mengpaw-design-system (AAR) — Arco 主题/组件
    └── Intent → mengpaw-browser (独立 APK, 不依赖 core)

plugins/plugin-* (9 个独立 Library 模块)
    └── mengpaw-core (微内核 AAR) — Plugin 接口 + CLI 类型
    └── [plugin-net 额外依赖 ktor-client]
    └── [plugin-ui 额外依赖 kotlinx-coroutines]
```

### 4.4 关键源文件

**mengpaw-core (微内核 39 源文件):**
- `AgentEngine.kt`: ReAct + runWithPlan，PluginManager 动态构建 Pipeline，IntegrityGuard + AgentDocManager 集成
- `cli/`: CliInterpreter, CommandRegistry (unregister), Pipeline (速率限制+审计日志)
- `plugin/`: Plugin 接口, PluginMetadata, PluginManager, PluginExecutor (plugin.* 命令), PluginMarketplaceClient (HTTPS+SSRF防护)
- `namespace/`: SelfExecutor (内置), SysExecutor (内置, 11命令), ScreenshotManager
- `agent/`: AgentDocManager (5文档), AgentExecutor (agent.* 命令), AgentProfile
- `security/`: SecurityPolicy (blockList审计), IntegrityGuard (fail-secure), Vault, Sanitizer (Base64精度优化), StorageMonitor
- `session/`: SessionManager (压缩), History, CheckpointManager
- `llm/`: LlmProvider, RemoteApi, AdaptiveLlmProvider (重试+降级), LocalModel, PromptEngine, LlmRequestBuilder
- `mcp/`: McpServer (Tools/Resources/Prompts, JSON-RPC)
- `acp/`: AcpProtocol (6消息类型), AcpServer (发现/委派/共享, 共享密钥认证)
- `extension/`: ManifestParser (manifest 解析，被 plugin/ 复用)
- `transport/`: AidlInterface, HttpServer(stub), UnixSocket

**插件模块 (21 个, plugins/ 目录):**
- `plugin-fs/`: FsPlugin (8 命令, 纯 JDK, plugin-manifest.json)
- `plugin-net/`: NetPlugin (3 命令, ktor-client, INTERNET 权限)
- `plugin-memory/`: MemoryPlugin (6 命令, MD 持久化, LRU 缓存, MemoryEntry)
- `plugin-skill/`: SkillPlugin (4 命令, YAML+Markdown, SkillDef)
- `plugin-self/`: SelfPlugin (4 命令, 纯 JDK — 独立版自省)
- `plugin-ui/`: UiPlugin (7 命令, coroutines.delay, AccessibilityService 权限)
- `plugin-proc/`: ProcPlugin (3 命令, stub, exec 沙箱禁用)
- `plugin-clipboard/`: ClipboardPlugin (3 命令, 内存存储)
- `plugin-notification/`: NotificationPlugin (3 命令, 内存存储, POST_NOTIFICATIONS 权限)

**mengpaw-shell (11 源文件):**
- `MainActivity.kt`: 入口 + `MengPawApp` 响应式导航 (NavigationBar/NavigationRail + 双栏布局)
- `ui/screens/MainScreen.kt`: Chat UI + 自适应建议卡片 (内容最大宽度 720dp 居中)
- `ui/screens/AgentViewModel.kt`: 连接 AgentEngine + 缺失插件检测 + PadPlugin 状态同步
- `ui/screens/PluginViewModel.kt`: 插件 ViewModel (市场/安装/状态/反射安全加载)
- `ui/screens/PluginMarketScreen.kt`: 插件市场 (双Tab + 安装进度动画)
- `ui/screens/PluginDetailScreen.kt`: 插件详情 (命令列表/权限/操作)
- `ui/screens/SettingsScreen.kt`: 精简设置 (API Key + PAD悬浮窗开关 + 插件管理入口)
- `ui/screens/SettingsViewModel.kt`: 设置状态管理
- `ui/screens/BrowserScreen.kt`: 内置浏览器 + Markdown/图片预览
- `ui/AdaptiveLayout.kt`: WindowSizeClass 计算 (Compact/Medium/Expanded 断点)
- `ui/localization/Strings.kt`: 中英双语注解 (术语括号注英文)

**已移除:** MemoriesScreen, SkillsScreen, AgentSettingsScreen, LogViewerScreen, ExtensionMarketScreen

**mengpaw-design-system (4 源文件):**
- `tokens/ArcoColors.kt`: 10 级色阶 (蓝/绿/橙/红/灰) + 语义 token (Text/Bg/Border)
- `tokens/ArcoTypography.kt`: 排版 token (display/title/body/caption) + 间距/圆角/阴影/图标尺寸
- `theme/ArcoTheme.kt`: Light/Dark ColorScheme + 5级 Tonal Surface + `ThemeColors` 动态存取器
- `components/ArcoComponents.kt`: ArcoCard, ArcoBadge, ArcoEmpty, ArcoDivider (均使用 ThemeColors)

**mengpaw-browser (1 源文件):** BrowserActivity — 完整浏览器（后退/前进/刷新/URL 栏/进度条），Activity 含 3 组 intent-filter（LAUNCHER / VIEW http|https / OPEN_URL），configChanges=orientation|screenSize|keyboardHidden

### 4.5 测试（86 个，9 文件）

| 测试文件 | 内容 | 用例数 |
|----------|------|--------|
| AgentEngineTest | PlanStep/TaskPlan/ReAct 循环/状态转换 | 13 |
| AdaptiveLlmProviderTest | Provider 检测/配置/Fallback/异常 | 13 |
| SecurityTest | 脱敏/策略/拦截 | 13 |
| CliInterpreterTest | 分词/引号/转义/flags | 11 |
| ManifestParserTest | 版本/兼容/加载 | 10 |
| SessionManagerTest | 会话 CRUD/追踪 | 9 |
| PipelineTest | 执行/安全/文件读写 | 7 |
| PromptEngineTest | ReAct/中英文/循环检测 | 6 |
| CommandRegistryTest | 注册/命名空间/列表 | 4 |

---

## 5. CLI 规范

命令格式：`namespace.command arg1 arg2 "arg with spaces" --flag value`

转义用 `\`，引号用 `"..."`，标志用 `--name value`（无值标志 = `"true"`）。

### fs — 文件系统 (8)
`cat <path>` | `ls [path]` | `write <path> <content>` | `rm <path>` | `mkdir <path>` | `cp <src> <dst>` | `mv <src> <dst>` | `stat <path>`

### ui — 界面操控 (7)
`click <x> <y>` | `swipe <x1> <y1> <x2> <y2> [ms]` | `input <text>` | `screenshot [path]` | `back` | `home` | `wait <ms>`

截图默认路径：`/data/data/com.mengpaw/files/screenshots/session_{sessionId}.png`

### proc — 进程管理 (3)
`ps` | `kill <pid>` | `exec <command>`（SecurityPolicy 拦截，默认禁用）

### net — 网络 (3)
`curl <url>` | `get <url>`（curl 别名） | `post <url> <body>`

### self — 内省 (4)
`status` | `config [key=value]` | `stats` | `version`

### memory — 记忆 (6)
`ls` | `read <id>` | `write <id> <content>` | `rm <id>` | `search <query>` | `stats`

内置记忆文档（installDefaults 自动安装）：cli-reference（CLI 完整参考全文）、tool-index（命令快速索引）

### skill — Skill (4)
`ls` | `run <name>` | `enable <name>` | `disable <name>`

默认 Skill（installDefaults 自动安装）：搜索（net.curl 搜索）、总结（文件/网页摘要）、翻译（中英对照）

### clipboard — 剪贴板 (3)
`copy <text>` | `paste` | `clear`

通过 java.awt.Toolkit 反射实现（Desktop 兼容）。Android 环境需 AWT 可用。

### notification — 通知 (3)
`send --title "T" --content "C"` | `list` | `dismiss <id|--all>`

### sys — 系统信息 (11) ⚡内置
`battery` | `network` | `location` (需位置权限) | `cpu` | `memory` | `storage` | `camera` (基本相机信息) | `sensors` | `display` | `apps` (需应用列表权限) | `clipboard`
所有权限按需请求，无权限时返回提示而非崩溃。

### vision — 视觉识别 (3)
`capture [path]` | `ocr <image-path>` | `recognize <image-path>` · 需 CAMERA 权限

### audio — 听觉识别 (4)
`record [seconds]` | `stt <audio-file>` | `tts <text>` | `play <audio-file>` · 需 RECORD_AUDIO 权限

### agent — Agent 文档 (6) ⚡内置
`docs` | `memory [query]` | `memory.record <content>` | `cli` | `profile` | `soul`

### plugin — 插件管理 (10) ⚡内置
`marketplace [--refresh]` | `search <query>` | `install <id>` | `uninstall <id>` | `list` | `info <id>` | `enable <id>` | `disable <id>` | `update <id>` | `upgrade --all`

插件管理是核心能力，`plugin.*` 命令始终可用。Agent 可：
1. `plugin.marketplace` 拉取市场索引（ETag 缓存 5 分钟）
2. `plugin.search <query>` 搜索插件
3. `plugin.install <id>` 下载+SHA256 校验+安装+激活
4. `plugin.list` 查看已安装插件及状态
5. Agent 可通过 LLM 创建 SCRIPT 类型插件并 `plugin.publish` 到社区

Stub 实现（内存存储最多 20 条），正式版需 NotificationListenerService。

---

## 6. 安全模型

### 6.1 三层拦截

命令 → ① 白名单（allowList） → ② 黑名单（blockList: proc.exec） → ③ 危险模式（restrictedPatterns: rm -rf /, chmod 777 / 等） → 执行

### 6.2 Vault (API Key 存储)

SharedPreferences 沙箱隔离。不依赖 security-crypto 库（Android 文件级加密已足够）。

### 6.3 Sanitizer (日志脱敏)

自动识别并替换：OpenAI Key (`sk-proj-*` → `***REDACTED_sk-p***`)、Anthropic Key (`sk-ant-*`)、Google Key (`AIza*`)、Bearer Token、40+ 字符 Base64 启发式。

---

## 7. 扩展开发指南

生命周期：发现 → 安装 → 加载 → 注册 → 调用 → 卸载

扩展 Manifest (JSON)：name, version, minCoreVersion, maxCoreVersion, apiVersion, packageName, permissions, capabilities。

ExtensionLoader 检查版本兼容性（`current ∈ [minCoreVersion, maxCoreVersion]`），超出范围拒绝加载。

内置扩展：Browser (✅), Device (⏳), Code (⏳), Network (⏳)

---

## 8. 开发路线图

- **Phase 1 ✅**: CLI 引擎、9 命名空间(41 命令)、三层安全拦截、Vault、会话管理(含压缩)、LLM 接口(含降级链)、Prefix Cache 优化、记忆系统(含被动索引)、Skill 系统(3 默认 Skill)、86 测试
- **Phase 2 ✅**: Chat UI、前台服务(dataSync)、扩展市场 UI、设置(6 提供商)、日志、记忆 UI、Skills UI、R8 瘦身 6.8MB
- **Phase 3 ✅**: 独立浏览器 APK (8.4MB)、导航工具栏、URL 栏、进度条、3 组 intent-filter、Arco 主题
- **Phase 4 ⏳**: Python CLI 工具、Unix Socket 客户端、命令补全、Termux 安装包
- **Phase 5 ⏳**: Device (AccessibilityService/MediaProjection/InputManager)、Code (QuickJS/Python 沙箱)
- **Phase 6 ⏳**: 扩展签名验证、在线扩展市场、示例工作流、社区文档、性能优化

---

## 9. API 参考

核心数据结构：

- **ExecutionResult**: success, output, error, exitCode
- **ParsedCommand**: command, args (List), flags (Map)
- **LlmProvider 接口**: complete(prompt) → String, completeStreaming(prompt, onToken) → String, completeWithMessages(messages: List<Map<String,String>>) → String, info() → ProviderInfo
- **AdaptiveLlmProvider**: 在 LlmProvider 基础上增加 AdaptiveConfig (maxTokens, temperature, timeoutMs, maxRetries, retryDelayMs, fallbacks: List<FallbackEntry>)
- **FallbackEntry**: apiEndpoint, apiKey, model
- **LlmRequestBuilder**: buildMessages(messages) → List<Map>, buildRequest(...) → JSON String, 跟踪 hit/miss tokens
- **AgentState**: Idle | Running(task, step, maxSteps) | Finished(result) | Error(message)
- **TaskPlan/PlanStep**: 计划执行模式的数据结构，PlanStepStatus: PENDING/RUNNING/COMPLETED/FAILED
- **ReActResponse**: thought, action (ToolCall?), isFinal
- **PromptEngine**: buildSystemPrompt() → String, parse(text) → ReActResponse, detectLoop(command) → Boolean
- **SessionManager**: createSession/addMessage/getStructuredHistory/compressIfNeeded/deleteSession
- **CheckpointManager**: save/loadLatest/cleanup (JSON 持久化到文件)
- **MemoryManager**: list/get/save/update/delete/search/stats/installDefaults (LRU 缓存 2s)
- **SkillManager**: list/get/save/delete/setEnabled/importFromText/installDefaults/generateIndex

### 支持的服务商

| 服务商 | Endpoint | 默认模型 | 认证方式 |
|--------|----------|----------|----------|
| OpenAI | api.openai.com | gpt-4o | Bearer |
| DeepSeek | api.deepseek.com | deepseek-chat | Bearer |
| Kimi | api.moonshot.cn | moonshot-v1-8k | Bearer |
| GLM | open.bigmodel.cn | glm-4-plus | 裸 Key (无 Bearer) |
| Qwen | dashscope.aliyuncs.com | qwen-plus | Bearer |
| Custom | 自定义 | 自定义 | Bearer |

### 被动索引系统

Agent 通过 memory 命令按需加载文档，而非一次性加载全部上下文：

- `memory read cli-reference` — CLI 完整参考（~400 行，CliReference.content）
- `memory read tool-index` — 命令快速索引（~30 行，MemoryManager.generateToolIndex()）
- `skill.ls` + `skill.run <name>` — 先看索引再加载具体 Skill

---

## 10. 开发工具链

本项目全程由 AI 辅助开发。不同阶段的工具链：

| 阶段 | 时间范围 | 编排工具 | 主力模型 | 产出 |
|------|---------|---------|---------|------|
| **早期** | 2026-07-12 ~ 07-15 | [Reasonix](https://github.com/reasonix-com/reasonix) | DeepSeek Flash | US-001 ~ US-012：品牌重塑、CLI 基础设施、安全测试、三审三校 |
| **中期** | 2026-07-16 ~ 至今 | [Claude Code](https://claude.ai/code) | DeepSeek Pro | 架构重构（plugin/acp/agent/mcp/trigger）、插件系统迁移、代码轻量化、GitHub 发布准备 |

模型推理通过 [DeepSeek API](https://api.deepseek.com) (`api.deepseek.com`)，配置文件：
- **Reasonix** — `reasonix.toml`（仓库根目录）
- **Claude Code** — `.claude/settings.local.json`（本地，已 gitignore）

---

## 11. 构建与部署

要求：Android SDK 35+, JDK 17+, Gradle 8.12 (Wrapper)

```bash
# 测试 (86 个)
./gradlew :mengpaw-core:testDebugUnitTest

# 主 APK
./gradlew :mengpaw-shell:assembleDebug     # Debug 21MB
./gradlew :mengpaw-shell:assembleRelease   # Release 6.8MB (R8+资源压缩)

# 浏览器 APK
./gradlew :mengpaw-browser:assembleDebug   # 8.4MB

# 清理
./gradlew clean
```

签名发布：`keytool -genkey -v -keystore mengpaw.keystore -alias mengpaw -keyalg RSA -keysize 2048 -validity 10000`

截图工具：`./scripts/screenshot.sh [名称]`，保存至 `.screenshots/`，最多保留 10 张。

### 主要依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.0.21 | 语言 |
| Compose BOM | 2024.12.01 | UI |
| kotlinx-serialization-json | 1.7.3 | LLM 请求/响应、记忆文件 |
| kotlinx-coroutines | 1.9.0 | 异步 |
| ktor-client (core+okhttp) | 3.0.3 | HTTP (net.curl, LLM API) |
| material-icons-extended | — | Shell 图标（完整） |
| material-icons-core | — | Browser 图标（轻量） |
| JUnit | 4.13.2 | 测试 |

---

## 12. 常见问题

- **两个 APK 关系**: Shell 是核心 Agent 应用；Browser 是独立浏览器，可互相唤醒也可独立使用
- **配置 API Key**: 设置 → 模型提供商 → 展开 Provider 卡片 → 选择模型 → 输入 Key。API Key 为空自动用模拟模式
- **每 Agent 不同模型**: 设置中配置只影响当前 Agent，不同 Agent 可分别用不同模型（顶栏显示）
- **LLM 调用失败**: AdaptiveLlmProvider 自动重试（指数退避），可配置 fallback 降级链
- **切换模型**: 设置中选新 Provider 即可，系统自动切换缓存策略、重置 token 校准、停旧引擎
- **Prefix Cache 优化**: DeepSeek/Grok/OpenModel → PREFIX_STABLE；OpenAI/Kimi/GLM/Qwen/火山 → CACHE_CONTROL
- **对话历史过长**: SessionManager 自动压缩（超 50 条触发 LLM 摘要），上限 200 条
- **历史会话**: 右侧侧边栏，自动保存，左滑删除/压缩。已压缩不可继续对话，只能引用
- **管理记忆**: 主界面 ⏱ 按钮，或 CLI `memory` 命令（含 2s LRU 缓存）
- **多标签页**: MP 浏览器支持 4 标签页并行，Agent 通过 `browser.tab.*` 命令操控
- **Agent 操控浏览器**: `browser.click/type/scroll/content/eval` 等命令，需先 `browser.inject` 注入桥
- **Mission 模式**: `mission.start` 自动分解任务，Worker 子 Agent 独立执行，Verifier 验证结果
- **发布流程**: 详见 [RELEASE.md](RELEASE.md)

---

## 13. 发布流程

详见 [RELEASE.md](RELEASE.md)。核心步骤：

1. `./gradlew clean assembleRelease` — 编译验证
2. 更新 CHANGELOG.md + 版本号
3. 构建 APK + 验证产物
4. commit + tag + push
5. `gh release create` 上传 APK + CHANGELOG

**红线**：编译不过不 push、不验证 APK 不上传、未经指令不发布。
- **剪贴板操作**: clipboard 命名空间，Desktop 环境通过 AWT 反射实现
- **APK 瘦身**: Release 自动 R8+资源压缩 (6.8MB)；进一步可移除 material-icons-extended、启用 AAB

---

## 13. 项目交接

### 12.1 环境搭建

1. JDK 17 (Amazon Corretto 17 推荐)
2. JAVA_HOME + ANDROID_HOME 环境变量
3. Android SDK 35 (platforms, build-tools, platform-tools, emulator)
4. 克隆 → `./gradlew :mengpaw-core:testDebugUnitTest` (86 PASS) → assembleDebug

> Gradle 下载已配置腾讯云镜像，中国大陆可用。

### 12.2 关键配置文件

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` (根) | AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, kotlin.plugin.compose+serialization |
| `settings.gradle.kts` | 4 模块注册 (core/shell/browser/design-system) |
| `mengpaw-core/build.gradle.kts` | kotlinx-serialization 1.7.3, ktor 3.0.3, coroutines 1.9.0, minSdk 26 |
| `mengpaw-shell/build.gradle.kts` | Compose, material-icons-extended, versionName 0.1.0-alpha |
| `mengpaw-browser/build.gradle.kts` | material-icons-core (轻量), versionName 1.0.0 |
| `mengpaw-shell/.../AndroidManifest.xml` | 4 权限, MainActivity, ShellService (foregroundServiceType=dataSync) |
| `mengpaw-browser/.../AndroidManifest.xml` | 2 权限, BrowserActivity (3 intent-filter: LAUNCHER/VIEW http|https/OPEN_URL), configChanges |
| `mengpaw-core/proguard-rules.pro` | R8 规则 |
| `mengpaw-shell/proguard-rules.pro` | Shell R8 规则 |

### 12.3 交接检查清单

- [ ] JDK 17 + Android SDK 35 + Gradle 8.12
- [ ] 86 测试全部通过
- [ ] Shell Debug APK 构建成功 (21MB)
- [ ] Browser Debug APK 构建成功 (8.4MB)
- [ ] Shell Release 构建成功 (6.8MB)
- [ ] 模拟器安装验证
- [ ] 通读本指南 + README + CONTRIBUTING

### 12.4 已知问题

| 问题 | 优先级 | 说明 |
|------|--------|------|
| Vault 使用基本 SharedPreferences | 中 | 生产环境可用 EncryptedSharedPreferences（添加 security-crypto 依赖） |
| HttpServer 为 stub | 低 | 需 Ktor Server 依赖 |
| Icons.Default.ArrowBack 弃用 | 低 | 迁移至 Icons.AutoMirrored.Filled.ArrowBack |
| proc.exec 永久禁用 | 中 | 需完善沙箱机制 |
| 无权限请求运行时处理 | 中 | INTERNET 等需运行时请求 |
| NotificationExecutor 为 stub | 中 | 需 NotificationListenerService |
| ClipboardExecutor 依赖 AWT 反射 | 低 | Android 环境需验证兼容性 |

### 12.5 待开发功能

| 功能 | 优先级 | 参考 |
|------|--------|------|
| Device 扩展（无障碍服务） | 高 | mengpaw-device 模块待创建 |
| Code 扩展（JS/Python 沙箱） | 高 | mengpaw-code 模块待创建 |
| CLI Python 工具 | 中 | mengpaw-cli 模块待创建 |
| 在线扩展市场 | 低 | ExtensionMarketScreen UI 已完成 |
| 浏览器书签/历史/多标签 | 低 | 底部工具栏已预留按钮 |
| 多语言支持（日/韩/法） | 低 | 基于现有 Strings.kt 框架 |

### 12.6 代码规范

- 包命名：`com.mengpaw.{模块}.{功能}`
- 类大驼峰，函数小驼峰
- UI 文字全部中文（Strings.kt 本地化）
- contentDescription 必须中文
- 注释中文

---

## 附录 A: 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| **0.3.0** | 2026-07-20 | **插件生态+浏览器操控+多模态+TV模块**: 26插件(新增10)、BrowserBridge双向桥、双侧面栏+会话历史、12 LLM Provider(Grok/火山/OpenModel/SelfHosted)、Mission Worker+Verifier、Agent Loop受控迭代、MengPaw TV、多模态vision message、TranslateMiddleware、15+BUG修复、ProGuard编译修复 |
| **0.2.2** | 2026-07-19 | **致命漏洞修复 + 插件开发体系**: DataPaths 动态初始化(修复闪退)、4轮安全审计(EventReceiver/HttpClient/状态串扰)、plugin-dev CLI(create/audit/share)、PLUGIN_DEV_GUIDE.md(安全规则+UI规范+模板) |
| 0.2.1 | 2026-07-19 | **多智能体+缓存优化+Dream模式**: 多会话架构(AgentSession隔离)、Reasonix 四级折叠、跨模型CacheStrategy、ScrollContext索引、中间件链、PromptBuilder锚点、DreamWorker充电触发、Markdown/Emoji渲染、框架通讯录层级、BigBangPopup |
| 0.2.0-alpha | 2026-07-17 | **UI重构+语音体系+梦境模式+安全增强**：左侧边栏、底部扩展面板、语音体系、梦境模式、Zero-overhead事件监听、21插件体系、QwenPaw安全规则 |
| 0.2.0-alpha | 2026-07-17 | **多Agent协作+ACP**: Hermes多Agent协作、PromptFirewall、ACP加密通信、MCP双向客户端、触发器引擎、多模型供应商管理 |
| 0.2.0-alpha | 2026-07-16 | **微内核+插件架构**：Plugin框架、PluginManager、PluginMarketplaceClient、plugin.* 命令族、9个独立插件模块、AgentEngine解耦Pipeline |
| 0.2.0-alpha | 2026-07-16 | **审美优化+深浅主题**: ThemeColors、WindowSizeClass、Browser设计统一、状态栏insets |
| 0.2.0-alpha | 2026-07-16 | **目录收敛+系统接口**: DataPaths、sys.* 系统信息、vision/audio插件、IntegrityGuard、Agent文档系统、MCP/ACP |
| 0.1.0-alpha | 2026-07-13 | 完整实现：CLI引擎、Chat UI、设置、记忆、Skill、独立浏览器、跨APK通信、R8瘦身 |

## 附录 B: 审校记录

| 日期 | 审校项 | 结果 |
|------|--------|------|
| 2026-07-19 | 四审四校 Crash漏洞 | DataPaths路径/文件IO/EventReceiver/HttpClient/状态串扰/!!空安全/假数据 — 全部修复 |
| 2026-07-19 | 插件版本统一 | 16插件回调至0.1.0，遵循SemVer |
| 2026-07-20 | v0.3.0 编译审查 | 7个编译错误修复: import遗漏/ProGuard/companion object重复/sealed class字段/跨模块依赖 |
| 2026-07-20 | 模型切换审查 | 15个stale state bug发现, 9个修复: cacheStrategy/tokPerChar/compactStuck/loopDetect/isRunning |
| 2026-07-20 | 闪退根因审查 | 13个问题发现: WebView线程池死锁/HttpClient泄漏/CookieManager/loadUrl循环/BroadcastReceiver泄漏 |
| 2026-07-19 | 代码静态审查 | 无硬编码路径、无!!强制解包、全量readText()保护 |
| 2026-07-16 | 全量源码审查+文档增量更新 | 修正文件数/命令数/测试数，补充9项新特性 |

---

*文档结束 · 最后更新: 2026-07-19*
