# MengPaw 🐾

[中文](README.md) | [English](README.en.md)

**面向 Agent 的自举式 Android 运行时框架** — 微内核 + 插件网格，Agent 通过内置 CLI 操控自身。

> **Agent = Model + Harness** — `mengpaw-kernel` 是纯 Kotlin/JVM 的 Harness 运行时本体（148 个源文件、零 Android 依赖、可 JVM 直测），Android 能力层与宿主 UI 围绕它分层搭建，可拆出适配任意系统。

## 💡 项目简介

MengPaw 是面向大语言模型与 AI Agent 的端侧运行时。它不做"更好的聊天 App"，而是做 App 之间的那个东西：**微内核负责编排，插件负责桥接，Agent 通过 CLI 命令直接操作系统**——不依赖截图识别和模拟点击。

中国的数字生态是被切碎的：微信能发消息不能管文件，米家能控小米设备不认华为，各种 AI 能聊天但"不活着"。用户不缺能力，缺的是**轮毂**。

```
应用层：微信 / 钉钉 / 米家 / 飞书 / WPS / ...     ← 碎片，不替代
Agent层：MengPaw 微内核 + 插件网格 + 记忆孪生    ← 轮毂，只做这个
设备层：手机 / 平板 / PC / 服务器 / ...            ← 节点，越多越强
```

> **用碎片打败碎片化。** 不是做一个更好的 App，是做 App 之间的那个东西。
> **用户即开发者。** 每个遇到碎片问题的人都能用 `plugin.create` 自建插件并分享。
> **局域网点对点是特征不是妥协**：数据不出局域网 = 隐私由物理定律保障，零服务器费用。

## 🚀 核心技术与架构亮点

### 1. 指令集与 Tools 路由

把 Android 能力统一成**点分命令**（`sys.app.launch`、`fs.cp`、`net.curl`、`twin.sync`），Agent 只需输出一行纯文本命令，不用理解 Intent 结构。

- **主路径是注册表**：`self.* / agent.* / sys.* / fs.* / net.* / skill.* / plugin.* / twin.* / fleet.*`，每个命令带参数签名预校验与风险分级。
- **未命中落 Linux 通道**（见下条），两条通道共用同一套安全判定。
- **缩短调用路径**：`self.search <自然语言>` 用 BM25 毫秒级检索命令（不把 22KB 命令全表塞进提示词），`self.tools [ns]` 按需枚举。
- **权限分级**：LOW 直接执行 / MID 需 Agent 权限提到"信任" / HIGH 每次弹窗确认，MID/HIGH 必须附 `reason` 意图声明。

### 2. Linux 兜底通道与常驻 Shell 池

在 Android 应用沙盒里，"Linux 能力"靠的是**常驻 `sh` 进程池**（`SessionShellPool`），而不是重新实现一套 shell：

- 命令经 `sh` 执行，`|`、`>`、`&&` 由真实 shell 处理；进程复用避免每条命令重启。
- **再解释形态递归检查**：`sh -c`、`am startservice`（Termux / 浏览器）、`su -c` 会被拆出 payload 递归再入同一套规则，防止嵌套绕过。
- 元字符策略、超时、超大输出截断外存三者齐备。
- 不做"虚拟文件描述符"或自造背压——没有这个必要，交给操作系统。

### 3. 系统能力接入（Intent / 无障碍 / Termux）

`sys` 命名空间共 93 条命令，覆盖：应用拉起与信息、通知、日历、剪贴板、截图录屏、相机、TTS、Toast/悬浮窗、电池与网络、联系人短信、USB、红外、壁纸等。

- **Intent**：`sys.app.launch`、`sys.intent.open/view/share` 走系统原生跳转。
- **无障碍**：`sys.accessibility.dump/click/swipe/input/back/home/recents` 提供 UI 自动化兜底（需用户在系统设置里手动开启服务）。
- **Termux 桥**：经 `am startservice` 的 `RUN_COMMAND` 逐项直传参数（不经 shell 字符串拼接），可直接跑 `apt`、配置 Python 环境。
- **未集成 Shizuku**：MengPaw 不提供 ADB 级权限；高权限操作依赖用户明确授权的无障碍服务或自带 Root 的 `plugin-root` 插件。

### 4. 跨端记忆孪生（Git 式账本）

**同步底座**：局域网 mDNS 发现 + 6 位短码配对 + ACP P2P 通道（HTTP/JSON，9876）+ 工作区清单比对与差异传输；心跳保活、QoS 自适应（WiFi/移动/计费）、手动 IP 兜底。同步范围含 `soul/profile/agents/boost/trigger/heartbeat`、`memory/` 全部与梦境产物，排除 `dialog/`、`inbox/`、`memory/backup/`。

**Git 式账本**（内容寻址，替换全量同步）：

- **对象库**：`blob / tree / snapshot` 三层，SHA-256 内容寻址 + zlib 压缩，`tmp`→`rename` 原子写，按可达性 GC。
- **协商**：先比根 tree 哈希 —— 相同则一轮结束（约 200 字节）；不同才下钻差异子树，只传缺失对象。
- **合并**：长期记忆走**三方合并**（base = 上次同步头），冲突写入文件交 Agent 裁决，不再整文件 LWW。
- **记账**：中期记忆**只增不改**（每条带会话/设备/逻辑时钟，编辑 = 新条目 + `supersedes`）；归纳产物降级为**可重建视图**且每条结论带来源指针，从根上消除"压缩坏记忆"。
- **设备清单**：每端只写 `devices/<id>.md` 自己那一行（硬件 / 模型 / 插件 / 服务），随同一对象库同步 —— Agent 因此知道自己寄存于哪台设备、其他端有什么能力。
- **短期对话不参与**（`dialog/` 保持排除）。
- **可回溯**：`memory.log / show / diff / restore / gc` 与 `device.self / list / info / find`；被删文件也能从对象库取回。

> 实施状态：P0（清单哈希收发形态统一 + 内容相同不落盘）已落地；P1–P4（对象库 / 协议 v0.3 / 记忆账本 / 设备库存）按设计文档推进，本节即其终态。

设计文档：[`docs/memory-git-sync-design.md`](docs/memory-git-sync-design.md)

### 5. 微内核与按需唤醒

- **内核零 Android 依赖**：`mengpaw-kernel` 纯 Kotlin/JVM，148 个源文件，可在 JVM 上秒级跑单测；Harness 抽象层已拆为独立仓库（`D:\MengPaw\harness`），换宿主只换适配层。
- **20 个 Gradle 模块**：内核 + Android 适配层 + 设计系统 + 宿主 Shell + 16 个内置插件；插件与内核同级、只依赖 kernel，不得互相依赖。
- **UI 是 Android 原生**：Jetpack Compose（Kotlin）。没有 Tauri、没有 Rust、没有 TypeScript。
- **按需唤醒**：前台服务保活 + CRON 触发器 + 事件驱动，不是"常驻监听"模型。

### 6. HITL 安全拦截

- **三级风险门禁** + `reason` 意图声明 + 危险操作弹窗（用户拒绝即阻断，Agent 必须如实告知）。
- **外源内容提纯**：`PromptFirewall`（注入检测 / 信任域）、`Sanitizer`（密钥脱敏）、`UntrustedContent`（不可信内容隔离）三层，外部数据默认不当作指令。
- **隔离执行**：Swarm 的 worker 各自独立会话，不污染主上下文。
- **API Key 是唯一安全禁区**：经 Android Keystore（Vault）持久化，不进日志、不进审计输出、不进仓库。

## 💻 技术栈

| 层 | 技术 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material3 + 自研 Arco 风格设计系统） |
| 构建 | Gradle 8.12（Wrapper）+ AGP 8.7.3，compileSdk 35 / minSdk 26 / JVM 17 |
| 微内核 | 纯 Kotlin/JVM，零 Android 依赖，`kotlinx-coroutines` + `kotlinx-serialization` |
| 端侧能力 | 注册表命令 + Linux 兜底通道（常驻 `sh` 池）+ Intent + 无障碍服务 + Termux 桥 |
| 跨端通信 | ACP over 局域网 HTTP/JSON（9876）+ mDNS（Android NSD）+ 配对密钥协商 |
| 存储 | 文件系统（Agent 工作区 / 会话检查点 / 工具结果外存 / 进化档案）+ 内容寻址对象库（SHA-256 / zlib / 快照 DAG） |
| 安全 | Vault（Android Keystore）/ IntegrityGuard（APK 签名）/ PromptFirewall / CommandMonitor |
| AI 协同 | OpenAI 兼容多供应商（11 个预置）+ 可选 SSH 连接器（Claude Code / Reasonix / Trae） |

## 🎯 典型应用场景

**分布式编程**：笔记本生成 Android 项目代码 → 台式机编译 → 手机端 Termux 自动安装测试。
当前可用的链路是：`fleet.delegate` 委派任务 → 对端 Agent 自主执行 → `fleet.send` 任意格式文件互传（64MB，落 `Fleet共享/`）→ 工作区同步回传结果。**Git 账本流转是设计目标，尚未落地**。

**复杂自动化运维**：一句自然语言 → Agent 经无障碍/GUI 通道打开隐藏设置、拉起服务 → Termux 管道处理海量日志 → 结果回到对话。

## 快速开始

```bash
git clone https://github.com/WowBlueStudio/MengPaw.git
cd MengPaw
./gradlew :mengpaw-shell:assembleDebug

# APK 位于:
# mengpaw-shell/build/outputs/apk/debug/mengpaw-shell-debug.apk
```

## 项目结构

```
MengPaw/
├── mengpaw-kernel/             # 微内核 (纯 Kotlin/JVM, 零 Android, 148 源文件)
│   ├── cli/                    # CLI 引擎 (解析→安全→执行→审计) + Linux 兜底通道
│   ├── security/               # Sanitizer / Policy / IntegrityProvider / PromptFirewall / CommandMonitor
│   ├── session/                # 会话管理 (历史压缩 / 断点保存 / 事件日志)
│   ├── llm/                    # LLM 接口 (多供应商自适应 / 流式分离 / Prefix Cache)
│   ├── plugin/                 # 插件框架 (生命周期 / 市场 / 版本兼容)
│   ├── agent/                  # Agent 文档 / 记忆三轨 / 梦境引擎 / 四模式调度
│   ├── acp/                    # Agent Communication Protocol (设备间通信)
│   ├── mcp/                    # Model Context Protocol (JSON-RPC)
│   ├── trigger/                # CRON + 伪人模式触发器
│   ├── AgentEngine.kt          # ReAct 引擎
│   └── DataPaths.kt            # 平台无关路径门面 (委托 harness)
│
├── mengpaw-core/               # Android 适配层 (35 源文件)
│   ├── security/               # Vault (Keystore) / IntegrityGuard (APK 签名)
│   └── namespace/sys/          # sys 命名空间 (93 命令)
│
├── mengpaw-design-system/      # 设计系统 (Material3 + Arco 风格)
│
├── mengpaw-shell/              # 主应用 APK (128 源文件)
│   ├── ui/screens/             # 对话 / 设置 / 插件市场 / 侧边栏
│   └── service/                # 前台服务 / 事件监听 / 唤醒
│
└── plugins/                    # 16 个内置插件 (同级，均只依赖 kernel，随 APK 捆绑)
    ├── plugin-net/             # HTTP 网络
    ├── plugin-skill/           # 双层技能系统 ⭐💎
    ├── plugin-clipboard/       # 剪贴板
    ├── plugin-office/          # Office 文档 Word/Excel/PPT
    ├── plugin-framework/       # 框架通信与局域网发现 (15 命令) ⭐💎
    ├── plugin-memory-twin/     # 记忆孪生 (17 命令) ⭐💎
    ├── plugin-agent-tools/     # 外部命令集导入 ⭐💎
    ├── plugin-root/            # Root 权限
    ├── plugin-hermes/          # 部落协作 Tribe 💎
    ├── plugin-termux/          # Termux 桥
    ├── plugin-dream/           # 梦境模式 (内置不可移除) ⭐
    ├── plugin-evolution/       # 智能体进化 (内置不可移除) ⭐
    ├── plugin-dev/             # 插件开发工具链 ⭐💎
    ├── plugin-tavily/          # AI 搜索
    ├── plugin-update/          # 应用内更新 ⭐
    └── plugin-concise/         # 言简意赅 (停用即恢复原提示词) ⭐
```

> ⭐ = 随 Shell APK 捆绑 · 💎 = WowBlue 原创（见下节）
>
> **浏览器已拆为独立仓库** [`WowBlueStudio/MengPaw-Browser`](https://github.com/WowBlueStudio/MengPaw-Browser)（独立版本线，经 JitPack 依赖本仓库共享地基）。
> **12 个外置插件**（连接器 / 生图 / 搜索 / 错误上报等）源码与分发在 [`mengpaw-connectors`](https://github.com/WowBlueStudio/mengpaw-connectors)（MIT），经插件市场 `plugin.install` 安装。

## 架构分层

```
┌─────────────────────────────────────┐
│  Shell APK（Compose UI）             │  ← 宿主与界面
├─────────────────────────────────────┤
│  mengpaw-core（35 文件）             │  ← Android 适配（sys/vault/签名校验）
├─────────────────────────────────────┤
│  mengpaw-kernel（148 文件）          │  ← 微内核（纯 Kotlin/JVM）
│  零 Android 依赖 · 可 JVM 直测       │
├─────────────────────────────────────┤
│  16 内置插件（同级 · 只依赖 kernel） │  ← 插件层
└─────────────────────────────────────┘
        ↕ ACP 9876（局域网 P2P）
   手机 / 平板 / PC（Termux、SSH 连接器）
```

## 核心概念

### 命令命名空间

| 命名空间 | 示例命令 | 职责 |
|---------|---------|------|
| `fs` | `cp`, `mv`, `stat`, `grep`, `glob` | 文件系统 |
| `net` | `curl`, `get`, `post`, `proxy` | HTTP 网络 |
| `sys` | `app.launch`, `notification.send`, `accessibility.dump`, `screenshot` | Android 系统（93 命令） |
| `skill` | `ls`, `run`, `create`, `pull`, `push` | 双层技能池 |
| `self` | `status`, `tools`, `search`, `ports`, `time` | Agent 自我管理 |
| `agent.memory` | `keep`, `record`, `project.save`, `search`, `stats` | 记忆三轨（18 命令） |
| `evolution` | `audit`, `report`, `learn.command`, `mark-corrected` | 从失败中学习 |
| `plugin` | `marketplace`, `install`, `search` | 插件管理 |
| `twin` | `peers`, `sync`, `delegate`, `route`, `model` | 记忆孪生（17 命令） |
| `fleet` | `delegate`, `reply`, `send`, `scan`, `capability` | 多设备编队 |

### 执行模式

- **ReAct**（默认）：Thought → Action → Observation 循环，Agent 自行决定下一步。
- **斜杠模式菜单**（`modes.md` 权威定义）：`/Swarm`（并行 Worker + Verifier）、`/Fleet`（跨设备编队）、`/Goal`（目标驱动 + 完成度评估）、`/Plan`（先规划后执行）、`/Research`（多轮检索交叉验证）、`/Silent`（后台静默执行）。

## WowBlue 原创插件

领先于同类 Agent 框架的原创设计（设置页与插件市场显示粉色 WowBlue 徽标）：

| 插件 | 命名空间 | 领先之处 |
|------|---------|---------|
| **记忆孪生** 💎 | `twin` | 跨设备同步整个 Agent 工作区（`soul.md` → `memory/`）：清单比对 + 差异传输 + ACP P2P + 短码配对 + 心跳保活 + QoS 自适应；Git 式账本演进中 |
| **部落协作** 💎 | `tribe` | 多 Agent 编队 — 局域网自动组队 + Kanban 委派（优先级/超时/嵌套链）+ LLM 能力路由 + 广播讨论 |
| **记忆系统** 💎 | `agent.memory` | 单轨三轨制 — 长期/中期/项目分层 + 跨轨搜索统计；梦境读中期 → 提炼 `{date}_dream.md`，只注入长期记忆防提示词膨胀降智 |
| **技能系统** 💎 | `skill` | 双层技能池 — 全局共享 + Agent 本地私有，按需 pull/push |
| **框架发现** 💎 | `framework` | mDNS 局域网框架发现 — 扫描/指纹/信任管理，多设备自动组网 |
| **插件开发链** 💎 | `dev` | 内置工具链 — `plugin.create / audit / share` 三步发布，用户即开发者 |
| **进化** 💎 | `evolution` | 从失败中学习 — 命令失误钩子 + 金字塔四层自问 + 错误四分法处置 |
| **Agent 命令集** 💎 | `tools` | 导入外部 CLI 命令集（gh / 飞书等）注册 per-agent 索引，紧凑摘要注入提示词 |

> 不造轮子造轮毂——这些能力不是替代生态，而是把碎片桥接成整体。

## 支持的 LLM 提供商

11 个预置（OpenAI / DeepSeek / Kimi / GLM / DashScope / Grok / 火山方舟 / MiniMax / OpenModel / Self-Hosted / Custom），当前旗舰型号如下：

| 预置 | 端点 | 默认型号 |
|------|------|---------|
| OpenAI | api.openai.com | gpt-5.6（1.05M 上下文） |
| DeepSeek | api.deepseek.com | deepseek-flash（多模态） |
| Kimi | api.moonshot.cn | kimi-k3（1M 上下文） |
| GLM | open.bigmodel.cn | glm-5.2（1M 上下文） |
| DashScope | dashscope.aliyuncs.com | qwen3.8-max（视觉+推理） |
| Grok | api.x.ai | grok-4.6（500K 上下文） |
| 火山引擎 | ark.cn-beijing.volces.com | doubao-seed-2.0-pro |
| MiniMax | api.minimaxi.com | MiniMax-M3（1M 上下文） |
| OpenModel | api.openmodel.ai | ⚠ 仅 Responses/Messages 协议，当前预置不可用 |
| Self-Hosted | 自定义 | local-model |
| Custom | 用户自填 | — |

> 完整型号清单与官方文档核对日期见 [`docs/add-llm-provider.md`](docs/add-llm-provider.md) §2（与 `SettingsModels.kt` 同步铁律）。

## 构建与测试

- Android SDK 35 + JDK 17 + Gradle 8.12（Wrapper 已包含）
- AGP 8.7.3 / Kotlin 2.0.21 / JVM target 17

```bash
# 微内核测试（JVM，秒级，无需模拟器）
./gradlew :mengpaw-kernel:test

# 插件测试
./gradlew :plugin-memory-twin:testDebugUnitTest

# 编译
./gradlew :mengpaw-shell:assembleDebug
```

## 维护与文档

- 自 2026-08-07 起由 Codex 接管维护，模型推理经 DeepSeek API 及多供应商切换。
- **唯一权威知识中心**：[`MengPaw-Development-Guide.md`](MengPaw-Development-Guide.md)（代码变更必须同步更新对应章节）
- **文档索引**：[`docs/INDEX.md`](docs/INDEX.md)（按场景选读）
- **经验教训库**：[`docs/lessons.md`](docs/lessons.md)

## 许可证（双许可）

| | 社区版 | 商业版 |
|---|--------|--------|
| 许可 | AGPL-3.0（[LICENSE](LICENSE)） | 商业授权（[COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)） |
| 费用 | 免费 | 付费 |
| 适用 | 个人使用 / 开源 / 遵守 AGPL 义务的部署 | 闭源分发 / 白标 / 嵌入产品 |

> 企业内部自用（不对外分发）不受限。商用授权咨询：1138018324@qq.com
> 外置连接器插件（[mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)）为独立 MIT 许可。

## 反馈与贡献

- **Bug 报告 / 功能请求**：GitHub [Issues](https://github.com/WowBlueStudio/MengPaw/issues)（附模板）
- **代码贡献**：开放 Pull Request，插件/文档类优先（提交即同意版权让渡，详见 [CONTRIBUTING.md](CONTRIBUTING.md)）
