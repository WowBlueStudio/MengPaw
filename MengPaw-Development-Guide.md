# MengPaw 开发文档

> **版本**: 0.1.0-alpha  
> **更新日期**: 2026-07-13  
> **项目定位**: 面向 Agent 的自举式 Android 操作系统框架  
> **交接状态**: ✅ 可移交

---

## 目录

1. [项目概述](#1-项目概述)
2. [架构设计](#2-架构设计)
3. [核心概念](#3-核心概念)
4. [模块体系](#4-模块体系)
5. [CLI 规范](#5-cli-规范)
6. [安全模型](#6-安全模型)
7. [扩展开发指南](#7-扩展开发指南)
8. [开发路线图](#8-开发路线图)
9. [API 参考](#9-api-参考)
10. [构建与部署](#10-构建与部署)
11. [常见问题](#11-常见问题)

---

## 1. 项目概述

### 1.1 什么是 MengPaw

MengPaw（檬爪）是一个**面向 Agent 的 Android 自举式操作系统框架**。它的核心设计哲学是：

> **Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

与传统 Agent 框架不同，MengPaw 不是给用户交互的工具，而是给 Agent 自己的"身体"——Agent 可以读写文件、操控 UI、管理进程、浏览网页、执行代码，就像一个拥有数字之爪的自主实体。

### 1.2 核心特征

| 特征 | 说明 |
|------|------|
| **自举式** | Agent 通过 CLI 调用自己的能力，形成自我操作闭环 |
| **零 Python** | 纯 Kotlin 实现，无需嵌入 Python 运行时 |
| **微内核** | 基础 APK 仅含核心，功能通过扩展包动态加载 |
| **多通道** | 同时支持 AIDL（系统集成）、Unix Socket（Termux）、HTTP（调试） |
| **可见浏览器** | 独立浏览器 APK，支持 WebView 完整导航（后退/前进/刷新/主页） |
| **跨 APK 通信** | 主应用和浏览器 APK 通过 Intent 互相唤醒，可独立使用 |
| **多模型支持** | OpenAI / DeepSeek / Kimi / GLM / Qwen / 自定义 一键切换 |
| **记忆系统** | Markdown 格式的持久化记忆，Agent 和用户可共同编辑 |
| **Skill 系统** | 可启停/导入的 Agent 剧本，YAML frontmatter + Markdown |

### 1.3 适用场景

- **自动化研究**: Agent 自主搜索、比价、整理资料
- **RPA 替代**: 无需 PC，手机上完成业务流程自动化
- **数字助手**: 真正可操作手机的智能助手（不只是聊天）
- **渗透测试**: 可控的浏览器自动化与设备操控
- **边缘 AI**: 完全离线的本地 Agent 运行环境

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         MengPaw 生态系统                             │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                      MengPaw-Shell (主 APK)                      │ │
│  │                                                                  │ │
│  │   ┌─────────────┐    ┌─────────────────────────────────────┐    │ │
│  │   │   LLM Core  │◄──►│         MengPaw-Core (微内核)        │    │ │
│  │   │  (推理决策)  │    │                                     │    │ │
│  │   │             │    │  ┌─────────┐ ┌─────────┐ ┌────────┐ │    │ │
│  │   │  本地模型    │    │  │ CLI 引擎 │ │ 安全沙箱 │ │ 会话管理│ │    │ │
│  │   │  或云端 API │    │  │         │ │ (API Key│ │        │ │    │ │
│  │   │  (通过安全   │    │  │ 命令解析 │ │  隔离)  │ │ 上下文  │ │    │ │
│  │   │   Vault 调用)│   │  │ 执行路由 │ │         │ │ 持久化  │ │    │ │
│  │   └─────────────┘    │  └─────────┘ └─────────┘ └────────┘ │    │ │
│  │                      │                                     │    │ │
│  │                      │  ┌─────────────────────────────┐   │    │ │
│  │                      │  │      内置 CLI 命名空间        │   │    │ │
│  │                      │  │ fs │ ui │ proc │ net │ self │   │    │ │
│  │                      │  │ memory │ skill               │   │    │ │
│  │                      │  └─────────────────────────────┘   │    │ │
│  │                      └─────────────────────────────────────┘    │ │
│  │                              │                                  │ │
│  │           ┌──────────────────┼──────────────────┐               │ │
│  │           │                  │                  │                │ │
│  │           ▼                  ▼                  ▼                │ │
│  │    ┌──────────┐      ┌──────────┐      ┌──────────┐           │ │
│  │    │ 本地文件  │      │ 系统 UI  │      │ 网络请求  │           │ │
│  │    │ 系统      │      │ 操控     │      │          │           │ │
│  │    └──────────┘      └──────────┘      └──────────┘           │ │
│  │                                                                  │ │
│  │                      │ (Intent)                                  │ │
│  └──────────────────────┼──────────────────────────────────────────┘ │
│                         │                                            │
│    ┌────────────────────┼────────────────────┐                       │
│    │                    │                    │                        │
│    ▼                    ▼                    ▼                        │
│ ┌─────────────┐  ┌─────────────┐  ┌────────────────────┐           │
│ │MengPaw-     │  │MengPaw-     │  │MengPaw-            │           │
│ │Browser      │  │Device       │  │Code                │           │
│ │(独立 APK)   │  │(扩展)       │  │(扩展)               │           │
│ │             │  │             │  │                    │           │
│ │• 后退/前进  │  │• 点击/滑动  │  │• JS/Python         │           │
│ │• URL 导航   │  │• 截图       │  │  沙箱执行           │           │
│ │• 独立使用   │  │• ADB Shell │  │• 文件生成           │           │
│ │• 可被主应用  │  │• 应用启动   │  │                    │           │
│ │  通过 Intent │  │             │  │                    │           │
│ │  唤醒       │  │             │  │                    │           │
│ └─────────────┘  └─────────────┘  └────────────────────┘           │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                      外部接口层                                   │ │
│  │                                                                  │ │
│  │   AIDL (系统APP) │ Unix Socket (Termux) │ HTTP (调试/第三方)     │ │
│  │                                                                  │ │
│  │   mp browser navigate "..."                                      │ │
│  │   mp device screenshot                                          │ │
│  │   mp fs cat /path/to/file                                       │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 跨 APK 通信

```
┌──────────────────┐         Intent (OPEN_URL)         ┌──────────────────┐
│  MengPaw Shell   │  ──────────────────────────────►   │  MengPaw Browser │
│  (com.mengpaw    │    com.mengpaw.action.OPEN_URL     │  (com.mengpaw    │
│   .shell)        │    Extra: url="https://..."        │   .browser)      │
│                  │  ◄──────────────────────────────   │                  │
│  21MB (Debug)    │    Launch Intent (MAIN)            │  8.4MB (Debug)   │
│  6.8MB (Release) │                                    │                  │
└──────────────────┘                                    └──────────────────┘

双方互相检测对方是否安装（仅在启动时检测一次），未安装时静默回退：
- 主应用未安装浏览器 → 回退内置 WebView (BrowserScreen)
- 浏览器未检测到主应用 → 隐藏「MengPaw」唤醒按钮
```

### 2.3 数据流

```
用户输入
    │
    ▼
┌─────────────┐
│  LLM Core   │ ── 生成 CLI 命令序列
│  (推理决策)  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ CLI Interpreter │ ── 解析命令，安全校验
│  (命令解析器)  │
└──────┬──────┘
       │
       ├──► 内置命名空间 (fs/ui/proc/net/self/memory/skill)
       │
       └──► 扩展命名空间 (ext browser/device/code)
                │
                ▼
           ┌─────────────┐
           │ AIDL / Socket│ ── 跨进程调用
           │  (传输层)     │
           └──────┬──────┘
                  │
                  ▼
           ┌─────────────┐
           │ 扩展 APK     │ ── 执行具体操作
           │ (MengPaw-*) │
           └──────┬──────┘
                  │
                  ▼
           ┌─────────────┐
           │  执行结果     │ ── 返回 LLM
           │  (Observation)│
           └─────────────┘
```

---

## 3. 核心概念

### 3.1 ReAct 循环

MengPaw 的 Agent 核心基于 **ReAct（Reasoning + Acting）** 模式：

```
Thought（思考） → Action（行动） → Observation（观察） → ... → Final Answer
```

每次循环：
1. **LLM 思考**: 分析当前状态，决定下一步
2. **生成命令**: 输出 CLI 命令（如 `fs cat /path/to/file`）
3. **执行命令**: CLI 引擎解析并执行
4. **观察结果**: 将执行结果反馈给 LLM
5. **重复或结束**: 直到任务完成或达到最大步数

### 3.2 命名空间（Namespace）

CLI 命令采用 `namespace command args...` 格式：

| 命名空间 | 职责 | 命令数 | 示例 |
|---------|------|--------|------|
| `fs` | 文件系统操作 | 8 | `fs cat /path/to/file` |
| `ui` | UI 操控 | 7 | `ui click 500 1200` |
| `proc` | 进程管理 | 3 | `proc ps` |
| `net` | 网络请求 | 3 | `net curl https://api.example.com` |
| `self` | 自我 introspection | 4 | `self status` |
| `memory` | 记忆系统 | 6 | `memory write note.md "内容"` |
| `skill` | Skill 系统 | 4 | `skill.run 搜索` |
| `ext` | 扩展调用 | — | `ext browser navigate "..."` |

**总计 35 个 CLI 命令**。

### 3.3 安全禁区

**API Key 是唯一禁区**：

- 存储位置: `/data/data/com.mengpaw/shared_prefs/mengpaw_vault.xml`
- 加密方式: Android 沙箱隔离（应用私有目录）
- 访问规则:
  - ✅ LLM Core 初始化时读取
  - ❌ 任何 CLI 命令无法访问
  - ❌ 日志自动脱敏
  - ❌ 扩展无法读取

---

## 4. 模块体系

### 4.1 模块清单

| 模块 | 名称 | 类型 | 状态 | 说明 |
|------|------|------|------|------|
| **mengpaw-core** | 微内核核心 | Library AAR | ✅ 完成 | CLI 引擎、安全沙箱、会话管理、LLM、记忆、Skill |
| **mengpaw-shell** | 主应用 | APK | ✅ 完成 | 用户入口、Chat UI、设置、记忆管理、Skills |
| **mengpaw-design-system** | 设计系统 | Library AAR | ✅ 完成 | Arco.design 适配 Compose 主题/组件 |
| **mengpaw-browser** | 浏览器 | APK (独立) | ✅ 完成 | 独立浏览器 APK，支持后退/前进/URL 导航 |
| **mengpaw-device** | 设备控制扩展 | APK (插件) | ⏳ 可扩展 | 点击、滑动、截图、ADB |
| **mengpaw-code** | 代码执行扩展 | APK (插件) | ⏳ 可扩展 | JS/Python 沙箱、代码生成 |
| **mengpaw-cli** | Termux CLI 工具 | Python CLI | ⏳ 可扩展 | Unix Socket 命令行工具 |

### 4.2 APK 大小

| APK | Debug | Release (R8) |
|-----|-------|--------------|
| MengPaw Shell | 21 MB | 6.8 MB |
| MengPaw Browser | 8.4 MB | — |

### 4.3 模块依赖关系

```
mengpaw-shell (主 APK)
    ├── mengpaw-core (AAR) — CLI 引擎、安全、LLM、记忆、Skill
    │       └── kotlinx-coroutines
    │       └── kotlinx-serialization
    │       └── ktor-client (for net.curl, API calls)
    │
    ├── mengpaw-design-system (AAR) — Arco 主题、组件
    │
    └── Intent → mengpaw-browser (独立 APK)
    
mengpaw-browser (独立 APK)
    └── mengpaw-design-system (AAR) — 与主应用一致的主题
```

### 4.4 目录结构

```
mengpaw/
│
├── README.md
├── LICENSE (Apache 2.0)
├── CONTRIBUTING.md
├── MengPaw-Development-Guide.md
├── PROJECT_SHOWCASE.html
│
├── build.gradle.kts              # 根构建 (AGP 8.7.3, Kotlin 2.0.21)
├── settings.gradle.kts           # 项目设置 (5 模块)
├── gradle.properties
├── local.properties
├── gradlew / gradlew.bat          # Gradle Wrapper 8.12
│
├── mengpaw-core/                  # ✅ Phase 1: 微内核核心
│   ├── src/main/kotlin/com/mengpaw/core/
│   │   ├── cli/
│   │   │   ├── CliInterpreter.kt      # 分词解析器
│   │   │   ├── CommandRegistry.kt     # 命令注册表
│   │   │   ├── CommandExecutor.kt     # 执行结果/上下文
│   │   │   └── Pipeline.kt            # 解析→安全→执行
│   │   ├── namespace/
│   │   │   ├── FsExecutor.kt          # 文件系统 (8 命令)
│   │   │   ├── UiExecutor.kt          # UI 操控 (7 命令)
│   │   │   ├── ProcExecutor.kt        # 进程管理 (3 命令)
│   │   │   ├── NetExecutor.kt         # HTTP 请求 (3 命令)
│   │   │   ├── SelfExecutor.kt        # 内省 (4 命令)
│   │   │   ├── MemoryExecutor.kt      # 记忆 CLI (6 命令)
│   │   │   ├── SkillExecutor.kt       # Skill CLI (4 命令)
│   │   │   └── ScreenshotManager.kt   # 截图路径管理
│   │   ├── memory/
│   │   │   └── MemoryManager.kt       # Markdown 记忆 CRUD
│   │   ├── skill/
│   │   │   └── SkillManager.kt        # Skill 管理 (YAML+Markdown)
│   │   ├── security/
│   │   │   ├── SecurityPolicy.kt      # 命令黑白名单
│   │   │   ├── Vault.kt               # SharedPreferences 存储
│   │   │   ├── Sanitizer.kt           # API Key 脱敏
│   │   │   └── StorageMonitor.kt      # 磁盘空间监控
│   │   ├── session/
│   │   │   ├── SessionManager.kt      # 会话管理
│   │   │   ├── History.kt             # 消息历史
│   │   │   └── Checkpoint.kt          # 断点续传
│   │   ├── llm/
│   │   │   ├── LlmProvider.kt         # Provider 接口
│   │   │   ├── LocalModel.kt          # 本地模型
│   │   │   ├── RemoteApi.kt           # 远程 API (OpenAI 兼容)
│   │   │   ├── AdaptiveLlmProvider.kt # 多模型适配层
│   │   │   └── PromptEngine.kt        # ReAct 解析引擎
│   │   ├── transport/
│   │   │   ├── AidlInterface.kt       # AIDL 接口定义
│   │   │   ├── HttpServer.kt          # HTTP 服务 (stub)
│   │   │   └── UnixSocket.kt          # Unix Socket 通道
│   │   └── extension/
│   │       ├── ExtensionLoader.kt     # 加载器（版本兼容）
│   │       ├── ExtensionApi.kt        # 扩展 API 契约
│   │       └── ManifestParser.kt      # 清单解析
│   ├── src/test/                      # 44 个单元测试
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
├── mengpaw-design-system/         # ✅ Arco.design 设计系统
│   ├── src/main/kotlin/com/mengpaw/design/
│   │   ├── tokens/
│   │   │   ├── ArcoColors.kt         # 色彩令牌
│   │   │   └── ArcoTypography.kt     # 排版/间距/圆角
│   │   ├── theme/
│   │   │   └── ArcoTheme.kt          # 亮/暗主题
│   │   └── components/
│   │       └── ArcoComponents.kt     # 基础组件
│   └── build.gradle.kts
│
├── mengpaw-shell/                 # ✅ 主应用 APK
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/mengpaw/shell/
│   │   │   ├── MainActivity.kt       # 入口 + 导航
│   │   │   ├── service/
│   │   │   │   └── ShellService.kt   # 前台服务
│   │   │   └── ui/
│   │   │       ├── localization/
│   │   │       │   └── Strings.kt    # 中英本地化
│   │   │       └── screens/
│   │   │           ├── MainScreen.kt          # Agent 聊天界面
│   │   │           ├── AgentViewModel.kt      # 聊天 ViewModel
│   │   │           ├── SettingsScreen.kt      # 设置界面
│   │   │           ├── SettingsViewModel.kt   # 设置 ViewModel
│   │   │           ├── MemoriesScreen.kt      # 记忆管理
│   │   │           ├── SkillsScreen.kt        # Skills 管理
│   │   │           ├── AgentSettingsScreen.kt # Agent 配置
│   │   │           ├── BrowserScreen.kt       # 内置浏览器(后备)
│   │   │           ├── PreviewScreen.kt       # Markdown/图片预览
│   │   │           ├── LogViewerScreen.kt     # 执行日志
│   │   │           └── ExtensionMarketScreen.kt # 扩展市场
│   │   └── res/values/
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
└── mengpaw-browser/               # ✅ 独立浏览器 APK
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   ├── kotlin/com/mengpaw/browser/
    │   │   └── BrowserActivity.kt    # 完整浏览器界面
    │   └── res/values/
    ├── build.gradle.kts
    └── extension-manifest.json
```

---

## 5. CLI 规范

### 5.1 命令格式

```
namespace.command arg1 arg2 "arg with spaces" --flag value
```

- **转义**: 反斜杠 `\` 转义下一个字符
- **引号**: 双引号 `"..."` 包裹的参数视为一个整体
- **标志**: `--name value` 或 `-n value`，无值标志设为 `"true"`

### 5.2 内置命名空间

#### `fs` — 文件系统 (8 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `fs.cat <path>` | path: 文件路径 | 读取文件内容 |
| `fs.ls [path]` | path: 可选目录 | 列出目录 |
| `fs.write <path> <content>` | path, content | 写入文件 |
| `fs.rm <path>` | path | 删除文件/目录 |
| `fs.mkdir <path>` | path | 创建目录 |
| `fs.cp <src> <dst>` | src, dst | 复制文件 |
| `fs.mv <src> <dst>` | src, dst | 移动文件 |
| `fs.stat <path>` | path | 文件详细信息 |

#### `ui` — 界面操控 (7 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `ui.click <x> <y>` | x, y | 点击坐标 |
| `ui.swipe <x1> <y1> <x2> <y2> [ms]` | 4 坐标 + 可选时长 | 滑动 |
| `ui.input <text>` | text | 输入文本 |
| `ui.screenshot [path]` | path: 可选路径 | 截图 |
| `ui.back` | — | 返回 |
| `ui.home` | — | 主页 |
| `ui.wait <ms>` | ms | 等待 |

#### `proc` — 进程管理 (3 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `proc.ps` | — | 进程列表 |
| `proc.kill <pid>` | pid | 终止进程 |
| `proc.exec <command>` | command | 执行命令(沙箱禁用) |

#### `net` — 网络 (3 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `net.curl <url>` | url | HTTP GET 请求 |
| `net.get <url>` | url | GET 别名 |
| `net.post <url> <body>` | url, body | POST 请求 |

#### `self` — 自我 introspection (4 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `self.status` | — | Agent 状态 |
| `self.config` | — | 配置信息 |
| `self.stats` | — | 内存/线程统计 |
| `self.version` | — | 版本信息 |

#### `memory` — 记忆系统 (6 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `memory.ls` | — | 列出所有记忆 |
| `memory.read <id>` | id | 读取记忆 |
| `memory.write <id> <content>` | id, content | 写入记忆 |
| `memory.rm <id>` | id | 删除记忆 |
| `memory.search <query>` | query | 搜索记忆 |
| `memory.stats` | — | 记忆统计 |

#### `skill` — Skill 系统 (4 命令)

| 命令 | 参数 | 说明 |
|------|------|------|
| `skill.ls` | — | 列出所有 Skill |
| `skill.run <name>` | name | 运行 Skill |
| `skill.enable <name>` | name | 启用 Skill |
| `skill.disable <name>` | name | 禁用 Skill |

---

## 6. 安全模型

### 6.1 三层拦截

```
输入命令
    │
    ▼
┌──────────────┐
│  ① 白名单    │  allowList: fs.cat, fs.ls, ... (只读命令)
│  ② 黑名单    │  blockList: proc.exec
│  ③ 危险模式  │  restrictedPatterns: rm -rf /, chmod 777 /, ...
└──────────────┘
    │
    ▼ (通过)
    执行
```

### 6.2 Vault (API Key 存储)

- 使用 Android `SharedPreferences`（应用私有目录，沙箱隔离）
- 如需增强加密，可添加 `security-crypto` 依赖启用 `EncryptedSharedPreferences`
- 存储路径: `/data/data/com.mengpaw/shared_prefs/mengpaw_vault.xml`

### 6.3 Sanitizer (日志脱敏)

自动识别并替换以下模式：

| 模式 | 示例 |
|------|------|
| OpenAI Key | `sk-proj-abc123...` → `***REDACTED_sk-p***` |
| Anthropic Key | `sk-ant-abc...` → `***REDACTED_sk-a***` |
| Google Key | `AIza...` → `***REDACTED_AIza***` |
| Bearer Token | `Bearer eyJ...` → `***REDACTED_bear***` |
| Base64 启发式 | 40+ 字符 Base64 → `***REDACTED_...***` |

---

## 7. 扩展开发指南

### 7.1 扩展生命周期

```
发现 → 安装 → 加载 → 注册 → 调用 → 卸载
```

### 7.2 扩展 Manifest

```json
{
  "name": "mengpaw-browser",
  "version": "1.0.0",
  "minCoreVersion": "0.1.0",
  "maxCoreVersion": "0.3.0",
  "apiVersion": 1,
  "packageName": "com.mengpaw.browser",
  "permissions": ["INTERNET"],
  "capabilities": {
    "browser": {
      "maxPages": 4,
      "supportsHeadless": true,
      "supportsScreenshot": true
    }
  }
}
```

### 7.3 版本兼容

```kotlin
class ExtensionLoader(private val coreVersion: String) {
    fun load(info: ExtensionInfo): Result<LoadedExtension> {
        val min = Version.parse(info.minCoreVersion)
        val max = Version.parse(info.maxCoreVersion)
        val current = Version.parse(coreVersion)
        require(current >= min) { "需要 Core >= ${info.minCoreVersion}" }
        require(current <= max) { "未测试 Core > ${info.maxCoreVersion}" }
        // ...
    }
}
```

### 7.4 内置扩展

| 扩展 | 包名 | 状态 |
|------|------|------|
| MengPaw Browser | `com.mengpaw.browser` | ✅ 独立 APK，已安装 |
| MengPaw Device | `com.mengpaw.device` | ⏳ 规划中 |
| MengPaw Code | `com.mengpaw.code` | ⏳ 规划中 |
| MengPaw Network | `com.mengpaw.network` | ⏳ 规划中 |

---

## 8. 开发路线图

### Phase 1: MengPaw-Core (已全部完成 ✅)

- [x] CLI 引擎（解析、注册、执行）
- [x] 内置命名空间（fs/ui/proc/net/self）
- [x] 安全沙箱（三层拦截）
- [x] Vault（API Key 隔离）
- [x] 会话管理（历史、上下文）
- [x] LLM 接口（本地 + 远程 + 多模型适配）
- [x] 记忆系统（Markdown CRUD）
- [x] Skill 系统（可启停/导入剧本）
- [x] 单元测试（44 个，覆盖率 > 70%）

### Phase 2: MengPaw-Shell (已全部完成 ✅)

- [x] 主 Activity（Chat UI, Reasonix 风格）
- [x] 前台服务（保活）
- [x] 扩展市场 UI
- [x] 设置界面（API Key/Provider/主题/语言）
- [x] 执行日志展示
- [x] 记忆管理 UI
- [x] Skills 管理 UI
- [x] 文件预览（Markdown + 图片）
- [x] APK 轻量化（R8 压缩至 6.8MB）

### Phase 3: MengPaw-Browser (已完成 ✅)

- [x] 独立浏览器 APK（8.4MB）
- [x] 导航工具栏（后退/前进/刷新/主页）
- [x] URL 栏 + 前往
- [x] 加载进度条
- [x] 与主应用互相唤醒（Intent 通信）
- [x] 支持 http/https 链接打开
- [x] Arco 主题一致

### Phase 4: MengPaw-CLI (待开始)

- [ ] Python CLI 工具
- [ ] Unix Socket 客户端
- [ ] 命令补全
- [ ] 输出格式化
- [ ] Termux 安装包

### Phase 5: MengPaw-Device & Code (待开始)

- [ ] Device: AccessibilityService
- [ ] Device: MediaProjection
- [ ] Device: InputManager
- [ ] Code: QuickJS 沙箱
- [ ] Code: Python via Termux

### Phase 6: 生态完善 (待开始)

- [ ] 扩展签名验证
- [ ] 在线扩展市场
- [ ] 示例 Agent 工作流
- [ ] 社区文档完善
- [ ] 性能优化

---

## 9. API 参考

### 9.1 CLI 执行结果

```kotlin
data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = if (success) 0 else 1
)
```

### 9.2 工具定义

```kotlin
data class ParsedCommand(
    val command: String,           // 如 "fs.cat"
    val args: List<String>,        // 位置参数
    val flags: Map<String, String> // 命名参数，如 --path /file
)
```

### 9.3 LLM 提供者

```kotlin
interface LlmProvider {
    suspend fun complete(prompt: String): String
    suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String
    fun info(): ProviderInfo
}
```

### 9.4 支持的服务商

| 服务商 | Endpoint | 默认模型 |
|--------|----------|----------|
| OpenAI | `api.openai.com/v1/chat/completions` | `gpt-4o` |
| DeepSeek | `api.deepseek.com/v1/chat/completions` | `deepseek-chat` |
| Kimi (月之暗面) | `api.moonshot.cn/v1/chat/completions` | `moonshot-v1-8k` |
| GLM (智谱) | `open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-4-plus` |
| Qwen (通义千问) | `dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-plus` |
| Custom | 自定义 | 自定义 |

### 9.5 Agent 状态

```kotlin
sealed class AgentState {
    data object Idle : AgentState()
    data class Running(val task: String, val step: Int, val maxSteps: Int) : AgentState()
    data class Finished(val result: String) : AgentState()
    data class Error(val message: String) : AgentState()
}
```

### 9.6 测试结果

```
🧪 44 个单元测试，全部通过
   ├── CliInterpreterTest     — 6 tests (解析/引号/转义/flags)
   ├── CommandRegistryTest    — 4 tests (注册/命名空间/列表)
   ├── PipelineTest           — 5 tests (执行/安全/文件读写)
   ├── SecurityTest           — 8 tests (脱敏/策略/拦截)
   ├── PromptEngineTest       — 6 tests (ReAct/中英文/循环检测)
   ├── SessionManagerTest     — 5 tests (会话CRUD/追踪)
   └── ManifestParserTest    — 10 tests (版本/兼容/加载)
```

---

## 10. 构建与部署

### 10.1 构建要求

- Android SDK 35+
- JDK 17+ (Amazon Corretto 17)
- Gradle 8.12 (Wrapper 已配置)

### 10.2 构建命令

```bash
# 完整构建 + 测试
./gradlew :mengpaw-core:testDebugUnitTest :mengpaw-shell:assembleDebug

# Release 构建 (R8 压缩)
./gradlew :mengpaw-shell:assembleRelease

# 浏览器 APK
./gradlew :mengpaw-browser:assembleDebug

# 清理
./gradlew clean
```

### 10.3 APK 产物

| 产物 | 路径 | 大小 |
|------|------|------|
| Shell Debug | `mengpaw-shell/build/outputs/apk/debug/` | 21 MB |
| Shell Release | `mengpaw-shell/build/outputs/apk/release/` | 6.8 MB |
| Browser Debug | `mengpaw-browser/build/outputs/apk/debug/` | 8.4 MB |

### 10.4 截图管理

```bash
# 截取模拟器截图并自动管理 (最多保留 10 张)
./scripts/screenshot.sh [名称]
```

截图保存在 `.screenshots/` 目录，超量自动删除最旧的。

### 10.5 依赖说明

| 依赖 | 用途 | 说明 |
|------|------|------|
| `kotlinx-serialization-json` | JSON 序列化 | LLM 请求/响应、记忆文件 |
| `ktor-client-core` + `okhttp` | HTTP 客户端 | `net.curl`、LLM API 调用 |
| `compose-bom` | Compose UI | UI 框架 |
| `material-icons-extended` | 图标库 | (仅 Shell 模块) |
| `material-icons-core` | 图标库 | (仅 Browser 模块，轻量) |

---

## 11. 常见问题

### 11.1 两个 APK 的关系

- **MengPaw Shell**: 核心 Agent 应用，包含 Chat UI、CLI 引擎、设置
- **MengPaw Browser**: 独立浏览器，可与 Shell 互相唤醒
- **分别安装**: 两者可独立使用，互不依赖

### 11.2 如何配置 API Key

打开设置 → 模型提供商 → 选择服务商 → 输入 API Key。

支持 OpenAI / DeepSeek / Kimi / GLM / Qwen / 自定义。

### 11.3 如何查看 Agent 执行日志

打开设置 → 执行日志，可查看所有历史命令执行记录。

### 11.4 如何管理记忆

主界面点击 ⭐ 按钮进入记忆管理，或通过 CLI `memory` 命令。

### 11.5 如何创建 Skill

1. 主界面点击 Skills 按钮
2. 点 + 安装默认 Skill（搜索/总结/翻译）
3. 或点 + 手动导入自定义 Skill（YAML frontmatter + Markdown 格式）

### 11.6 APK 瘦身建议

Release 构建自动启用 R8 + 资源压缩（6.8MB）。如需进一步减小：
- 移除 `material-icons-extended` 改为逐个导入核心图标
- 移除未使用的 Compose 组件
- 启用 Android App Bundle (.aab)

---

### 附录 A: 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 0.1.0-alpha | 2026-07-13 | 完整实现：CLI 引擎、Chat UI、设置、记忆、Skill、独立浏览器、跨 APK 通信、R8 瘦身 |


| 日期 | 审校项 | 结果 |
|------|--------|------|
| 2026-07-12 | 代码静态审查 | 修复 contentDescription 'Skills'→'技能'、移除 AndroidManifest 废弃 package |
| 2026-07-12 | 全量编译 + 44 测试 | BUILD SUCCESSFUL, ALL PASSED |
| 2026-07-12 | UI 中文全覆盖 | 所有界面硬编码英文已替换为中文 |
| 2026-07-13 | APK 轻量化 | Debug 21MB, Release R8 6.8MB (-68%) |
| 2026-07-13 | 跨 APK 通信 | 两个 APK 互相唤醒/跳转完成 |

---

## 12. 项目交接指南

> 本文档供接手开发者快速了解项目全貌，从零开始搭建开发环境并上手。

### 12.1 开发环境搭建（从零开始）

```bash
# 1. 安装 JDK 17
#   - 推荐 Amazon Corretto 17 或 Eclipse Temurin 17
#   - 下载: https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.msi

# 2. 配置环境变量
setx JAVA_HOME "C:\Program Files\Amazon Corretto\jdk17.0.19_10"
setx ANDROID_HOME "C:\Users\<用户名>\Android\Sdk"

# 3. 安装 Android SDK 35 (使用 sdkmanager)
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" "emulator"

# 4. 克隆并构建
git clone <repo-url>
cd mengpaw
./gradlew :mengpaw-core:testDebugUnitTest   # 运行测试
./gradlew :mengpaw-shell:assembleDebug       # 构建主 APK
./gradlew :mengpaw-browser:assembleDebug     # 构建浏览器 APK
```

> **注意**: GitHub 在中国大陆可能不可用，Gradle 下载地址已配置为腾讯云镜像。

### 12.2 项目结构速览

| 路径 | 说明 | 行数 |
|------|------|------|
| `MengPaw-Development-Guide.md` | 本文档 | 800+ |
| `README.md` | 项目简介 |
| `LICENSE` | Apache 2.0 |
| `CONTRIBUTING.md` | 贡献指南 |
| `PROJECT_SHOWCASE.html` | 可视化展示页面 |
| `scripts/screenshot.sh` | 截图工具（自动保留 10 张） |
| | |
| **mengpaw-core/** | **微内核核心模块 (33 源文件)** |
| `cli/` | CLI 引擎：CliInterpreter, CommandRegistry, Pipeline |
| `namespace/` | 内置命令：Fs, Ui, Proc, Net, Self, Memory, Skill (35 命令) |
| `memory/` | MemoryManager + CliReference |
| `skill/` | SkillManager (YAML+Markdown) |
| `security/` | SecurityPolicy, Vault, Sanitizer, StorageMonitor |
| `session/` | SessionManager, History, Checkpoint |
| `llm/` | LlmProvider, RemoteApi, AdaptiveLlmProvider, LocalModel, PromptEngine |
| `transport/` | AidlInterface, HttpServer (stub), UnixSocket |
| `extension/` | ExtensionLoader, ExtensionApi, ManifestParser |
| `AgentEngine.kt` | ReAct 循环 orchestrator |
| | |
| **mengpaw-shell/** | **主应用 APK (13 源文件)** |
| `MainActivity.kt` | 入口 + 导航 |
| `service/ShellService.kt` | 前台服务（保活） |
| `ui/screens/MainScreen.kt` | Agent 聊天界面 |
| `ui/screens/AgentViewModel.kt` | 聊天 ViewModel |
| `ui/screens/SettingsScreen.kt` | 设置界面（6 家 LLM 提供商） |
| `ui/screens/SettingsViewModel.kt` | 设置 ViewModel |
| `ui/screens/MemoriesScreen.kt` | 记忆管理（Markdown CRUD） |
| `ui/screens/SkillsScreen.kt` | Skills 管理（启停/导入） |
| `ui/screens/AgentSettingsScreen.kt` | Agent 配置（提示词/个性/约束） |
| `ui/screens/BrowserScreen.kt` | 内置浏览器（后备） |
| `ui/screens/PreviewScreen.kt` | Markdown + 图片预览 |
| `ui/screens/LogViewerScreen.kt` | 执行日志 |
| `ui/screens/ExtensionMarketScreen.kt` | 扩展市场 |
| `ui/localization/Strings.kt` | 中英文本地化 |
| | |
| **mengpaw-design-system/** | **Arco.design 设计系统 (4 源文件)** |
| | |
| **mengpaw-browser/** | **独立浏览器 APK (1 源文件)** |
| `BrowserActivity.kt` | 完整浏览器（后退/前进/刷新/URL 栏） |

### 12.3 关键文件索引

#### 需要关注的配置文件

| 文件 | 作用 |
|------|------|
| `build.gradle.kts` (根) | AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01 |
| `settings.gradle.kts` | 5 个模块注册 |
| `gradle.properties` | AndroidX, JVM 参数 |
| `mengpaw-core/build.gradle.kts` | 核心依赖：kotlinx-serialization, ktor-client, coroutines |
| `mengpaw-core/proguard-rules.pro` | R8 混淆规则 |
| `mengpaw-shell/build.gradle.kts` | Compose, material-icons-extended |
| `mengpaw-shell/proguard-rules.pro` | Shell R8 规则 |
| `mengpaw-browser/build.gradle.kts` | 浏览器 APK（轻量，仅 material-icons-core） |
| `mengpaw-shell/src/main/AndroidManifest.xml` | 权限声明, Activity, Service |
| `mengpaw-browser/src/main/AndroidManifest.xml` | 浏览器 intent-filter (http/https, OPEN_URL) |

#### 测试文件

| 文件 | 测试内容 | 用例数 |
|------|----------|--------|
| `cli/CliInterpreterTest.kt` | 分词/引号/转义/flags | 6 |
| `cli/CommandRegistryTest.kt` | 注册/命名空间/列表 | 4 |
| `cli/PipelineTest.kt` | 执行/安全/文件读写 | 5 |
| `security/SecurityTest.kt` | 脱敏/策略/拦截 | 8 |
| `llm/PromptEngineTest.kt` | ReAct/中英文/循环检测 | 6 |
| `session/SessionManagerTest.kt` | 会话 CRUD/追踪 | 5 |
| `extension/ManifestParserTest.kt` | 版本/兼容/加载 | 10 |
| **总计** | | **44** |

### 12.4 交接检查清单

接手开发者需完成以下步骤：

- [ ] **环境搭建**: JDK 17 + Android SDK 35 + Gradle 8.12
- [ ] **构建验证**: `./gradlew :mengpaw-core:testDebugUnitTest` → 44 PASS
- [ ] **主 APK**: `./gradlew :mengpaw-shell:assembleDebug` → 21MB APK
- [ ] **浏览器 APK**: `./gradlew :mengpaw-browser:assembleDebug` → 8.4MB APK
- [ ] **Release 构建**: `./gradlew :mengpaw-shell:assembleRelease` → 6.8MB
- [ ] **模拟器测试**: 创建 AVD → adb install → 验证功能
- [ ] **阅读文档**: 通读本指南 + README.md + CONTRIBUTING.md

### 12.5 已知问题与待办

#### 技术债务

| 问题 | 优先级 | 说明 |
|------|--------|------|
| `Vault` 使用基本 SharedPreferences | 中 | 生产环境应使用 EncryptedSharedPreferences（添加 security-crypto 依赖） |
| `HttpServer` 为 stub | 低 | 需要 Ktor Server 依赖才能启用 HTTP 调试接口 |
| `Icons.Default.ArrowBack` 弃用警告 | 低 | 应迁移至 `Icons.AutoMirrored.Filled.ArrowBack` |
| `proc.exec` 永久禁用 | 中 | 如需放开，需完善沙箱机制 |
| 无权限请求运行时处理 | 中 | INTERNET 等权限需运行时请求 |

#### 待开发功能

| 功能 | 优先级 | 参考 |
|------|--------|------|
| Device 扩展（无障碍服务） | 高 | `mengpaw-device` 模块骨架已创建 |
| Code 扩展（JS/Python 沙箱） | 高 | `mengpaw-code` 模块待创建 |
| CLI Python 工具 | 中 | `mengpaw-cli` 模块待创建 |
| 在线扩展市场 | 低 | ExtensionMarketScreen 已完成 UI |
| 浏览器书签/历史 | 低 | 底部工具栏已预留按钮 |
| 浏览器多标签 | 低 | 未来可基于 Chromium View |
| 多语言支持（日/韩/法） | 低 | 基于现有 Strings.kt 框架扩展 |

### 12.6 代码规范

```kotlin
// 1. 包命名: com.mengpaw.{模块}.{功能}
package com.mengpaw.core.cli

// 2. 类命名: 大驼峰
class CliInterpreter

// 3. 函数命名: 小驼峰
fun parse(input: String): ParsedCommand

// 4. UI 文字: 全部中文（通过 Strings.kt 本地化）
Text(s.appName)  // 而非 Text("MengPaw")

// 5. 注释: 中文注释为主
// 解析用户输入的 CLI 命令字符串

// 6. contentDescription: 必须中文
contentDescription = "返回"  // 而非 "Back"
```

### 12.7 APK 签名发布

```bash
# 生成签名密钥（仅首次）
keytool -genkey -v -keystore mengpaw.keystore -alias mengpaw -keyalg RSA -keysize 2048 -validity 10000

# Release 构建（自动签名需配置 signingConfigs）
./gradlew :mengpaw-shell:assembleRelease
./gradlew :mengpaw-browser:assembleRelease

# 产物路径
# mengpaw-shell/build/outputs/apk/release/mengpaw-shell-release.apk
# mengpaw-browser/build/outputs/apk/release/mengpaw-browser-release.apk
```

### 12.8 项目依赖

```
MengPaw Shell (21MB Debug / 6.8MB Release)
├── JDK 17
├── Android SDK 35
├── Gradle 8.12
├── Kotlin 2.0.21
├── Compose BOM 2024.12.01
├── Kotlinx Serialization 1.7.3
├── Ktor Client 3.0.3 (OkHttp)
├── Kotlinx Coroutines 1.9.0
├── AndroidX Activity Compose 1.9.3
├── AndroidX Lifecycle 2.8.7
│
MengPaw Browser (8.4MB Debug)
├── 同主应用，但使用 material-icons-core (轻量)
│
测试框架: JUnit 4.13.2 + Kotlinx Coroutines Test
```

### 12.9 联系方式与资源

- **项目文档**: `MengPaw-Development-Guide.md`（本文档）
- **展示页面**: `PROJECT_SHOWCASE.html`（浏览器打开查看可视化架构）
- **APK 产物**: 桌面 `MengPaw-v0.1.0-alpha.apk` / `MengPaw-Browser-v1.0.0.apk`
- **截图存档**: `.screenshots/` 目录（最多 10 张，自动管理）
- **构建日志**: `build/reports/problems/problems-report.html`

---

### 附录 C: 审校记录

| 日期 | 审校项 | 结果 |
|------|--------|------|
| 2026-07-12 | 代码静态审查 | 修复 contentDescription 'Skills'→'技能'、移除 AndroidManifest 废弃 package |
| 2026-07-12 | 全量编译 + 44 测试 | BUILD SUCCESSFUL, ALL PASSED |
| 2026-07-12 | UI 中文全覆盖 | 所有界面硬编码英文已替换为中文 |
| 2026-07-13 | APK 轻量化 | Debug 21MB, Release R8 6.8MB (-68%) |
| 2026-07-13 | 跨 APK 通信 | 两个 APK 互相唤醒/跳转完成 |
| 2026-07-13 | 被动索引系统 | tool-index / skill-index / cli-reference 三级索引 |
| 2026-07-13 | 内存优化 | MemoryManager LRU 缓存, SessionManager 200 条上限 |

---

*文档结束 · 最后更新: 2026-07-13*
