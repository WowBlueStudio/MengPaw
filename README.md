# MengPaw 🐾

面向 Agent 的自举式 Android 操作系统框架。

> **Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

## Why MengPaw

中国的数字生态是被切碎的——微信能发消息不能管文件，米家能控小米设备不认华为，各种 AI 能聊天但不能"活着"。用户不缺能力，缺的是**轮毂**。

MengPaw 走微内核编排路线：不造轮子造轮毂，用插件把已有的碎片桥接成一个整体。

```
应用层：微信 / 钉钉 / 米家 / 飞书 / WPS / ...     ← 碎片，不替代
Agent层：MengPaw 微内核 + 插件网格 + 记忆孪生    ← 轮毂，只做这个
设备层：手机 / 电脑 / 平板 / 车载 / ...            ← 节点，越多越强
```

> **用碎片打败碎片化。** — 不是做一个更好的 App，是做 App 之间的那个东西。
> **用户即开发者。** — 每个遇到碎片问题的人都能用 `plugin.create` 自建插件并分享。
> **不在一个平台上替代其生态，而是在每个平台上桥接其已有的碎片。**
>
> 局域网点对点是**特征**不是妥协：数据不出局域网 = 隐私由物理定律保障，零服务器费用 = 给普通人的东西。

## 快速开始

```bash
git clone https://github.com/WowBlueStudio/MengPaw.git
cd mengpaw
./gradlew :mengpaw-shell:assembleDebug

# APK 位于:
# mengpaw-shell/build/outputs/apk/debug/mengpaw-shell-debug.apk
```

## 项目结构

```
mengpaw/
├── mengpaw-kernel/             # 微内核 (pure Kotlin/JVM, 零 Android)
│   ├── cli/                    # CLI 引擎 (解析→安全→执行→审计)
│   ├── security/               # 安全层 (Sanitizer/Policy/IntegrityProvider/Firewall)
│   ├── session/                # 会话管理 (历史压缩/断点保存)
│   ├── llm/                    # LLM 接口 (多模型自适应/指数退避/Prefix Cache)
│   ├── plugin/                 # 插件框架 (生命周期/市场/版本兼容)
│   ├── agent/                  # Agent 文档管理 + 梦境引擎
│   ├── mcp/                    # Model Context Protocol (JSON-RPC)
│   ├── acp/                    # Agent Communication Protocol
│   ├── trigger/                # Cron + 真人感触发器
│   ├── namespace/              # 内置命名空间 (self)
│   ├── AgentEngine.kt          # ReAct + Plan-Execute 引擎
│   └── DataPaths.kt            # 平台无关路径常量
│
├── mengpaw-core/               # Android 适配层 (6 文件)
│   ├── security/               # Vault (Keystore) / IntegrityGuard (APK 签名)
│   └── namespace/              # SysExecutor (Android 系统信息)
│
├── mengpaw-design-system/      # Arco Design + Material3 主题
│
├── mengpaw-shell/              # 主应用 APK
│   ├── ui/screens/             # Chat/设置/插件市场/侧边栏
│   └── service/                # 前台服务/事件监听/唤醒
│
├── mengpaw-browser/            # 独立浏览器 APK
│   ├── bridge/                 # BrowserBridge (Java↔JS 双向桥)
│   └── plugin/                 # 浏览器内置插件 (22 命令)
│
└── plugins/                    # 24 个功能插件 (同级，均只依赖 kernel)
    ├── plugin-fs/              # 文件系统 (10 命令)
    ├── plugin-net/             # HTTP 网络 (3 命令)
    ├── plugin-memory/          # 记忆三轨系统 (6 命令) ⭐💎
    ├── plugin-skill/           # 双层技能系统 (4 命令) ⭐💎
    ├── plugin-self/            # Agent 自省 (4 命令)
    ├── plugin-clipboard/       # 剪贴板 (3 命令)
    ├── plugin-notification/    # 通知管理 (3 命令)
    ├── plugin-framework/       # mDNS 框架发现 (6 命令) ⭐💎
    ├── plugin-memory-twin/     # 记忆孪生 (24 命令) ⭐💎
    ├── plugin-agent-tools/     # Agent 命令集 (4 命令) ⭐💎
    ├── plugin-root/            # Root 权限 (17 命令)
    ├── plugin-hermes/          # 部落协作 Tribe 💎
    ├── plugin-dev/             # 插件开发工具链 ⭐💎
    ├── plugin-tavily/          # AI 搜索
    ├── plugin-workflow/        # DAG 工作流引擎
    ├── plugin-incubator/       # 子 Agent 孵化器
    ├── plugin-render/          # 图像生成
    ├── plugin-comfy/           # ComfyUI 集成
    ├── plugin-translate/       # 翻译
    ├── plugin-error-report/    # 错误上报
    ├── plugin-update/          # 自动更新
    ├── plugin-browser-push/    # 跨设备推送
    ├── plugin-browser-search/  # 搜索分析
    ├── plugin-browser-mcp/     # 浏览器 MCP
    ├── plugin-browser-cdp/     # Chrome DevTools
    └── plugin-browser-inspector/ # 元素检查器
```

> ⭐ = 捆绑在 Shell APK 中 · 💎 = WowBlue 原创（领先同类框架，见下节）

## 架构

```
┌────────────────────────────────┐
│  Shell APK     Browser APK     │  ← UI 层
├────────────────────────────────┤
│  mengpaw-core (6 文件)         │  ← Android 适配
├────────────────────────────────┤
│  mengpaw-kernel (46 文件)      │  ← 微内核 (纯 Kotlin/JVM)
│  零 Android 依赖 · 可 JVM 测试  │
├────────────────────────────────┤
│  24 插件 (同级 · 只依赖 kernel)  │  ← 插件层
└────────────────────────────────┘
```

## 核心概念

### Agent ReAct 循环

```
Thought（思考） → Action（行动） → Observation（观察） → ... → Final Answer
```

Agent 通过 CLI 命令操控设备：

| 命名空间 | 示例命令 | 职责 |
|---------|---------|------|
| `fs` | `cat`, `ls`, `write`, `grep` | 文件系统 |
| `net` | `curl`, `get`, `post` | HTTP 网络 |
| `memory` | `ls`, `read`, `write`, `search` | 记忆系统 |
| `sys` | `battery`, `cpu`, `display`, `wifi` | Android 系统 (39 命令) |
| `skill` | `ls`, `run`, `enable` | 技能系统 |
| `self` | `status`, `tools`, `search`, `time` | Agent 自省 |
| `plugin` | `marketplace`, `install`, `search` | 插件管理 |
| `twin` | `peers`, `sync`, `delegate`, `route` | 记忆孪生 |

### 执行模式

| 模式 | 说明 |
|------|------|
| **ReAct** | Thought → Action → Observation 标准循环 |
| **Plan-Execute** | LLM 分解任务为 3-7 步，逐步执行 |
| **Goal** | 单目标驱动 + LLM 自动评估完成度 |
| **Fleet** | LLM 拆解子任务 → Worker 执行 → Verifier 验证 |

## WowBlue 原创插件

领先于同类 Agent 框架的原创功能（设置页与插件市场显示粉色 WowBlue 徽标）：

| 插件 | 命名空间 | 领先之处 |
|------|---------|---------|
| **记忆孪生** 💎 | `twin` | 跨设备记忆同步 — 哈希链账本 + ACP P2P 加密通道 + 短码配对 + 心跳保活 + QoS 自适应，同类框架无此能力 |
| **部落协作** 💎 | `tribe` | 多 Agent 编队 — LAN 自动组队 + Kanban 委派（优先级/超时/嵌套链）+ LLM 能力路由 + 广播讨论 + 心跳检测 |
| **Agent 命令集** 💎 | `tools` | 导入外部 CLI 命令集（GitHub CLI / 飞书 CLI 等）注册 per-agent 索引，紧凑摘要注入系统提示词，快速调用无需遍历文档 |
| **记忆系统** 💎 | `memory` | 记忆三轨制 — 长期/中期/项目记忆分层，梦境模式按日压缩提炼，只注入长期记忆防提示词膨胀降智 |
| **技能系统** 💎 | `skill` | 双层技能池 — 全局池共享 + Agent 本地私有，skill.pull/push 按需拉取 |
| **框架发现** 💎 | `framework` | mDNS 局域网框架发现 — 注册/扫描/指纹记录/信任管理，多设备自动组网 |
| **插件开发链** 💎 | `dev` | 内置开发工具链 — plugin.create / audit / share 三步发布，用户即开发者 |

> 不造轮子造轮毂——这些能力不是替代生态，而是把碎片桥接成整体的原创设计。

## 构建要求

- Android SDK 35 + JDK 17 + Gradle 8.12 (Wrapper 已包含)
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

```bash
# 微内核测试 (JVM, 秒级，无需模拟器)
./gradlew :mengpaw-kernel:test

# 编译
./gradlew :mengpaw-shell:assembleDebug     # Shell APK
./gradlew :mengpaw-browser:assembleDebug   # Browser APK
```

## 开发工具

本项目由 AI 辅助开发，不同阶段使用的工具链：

| 阶段 | 时间 | 编排工具 | 主力模型 |
|------|------|---------|---------|
| 早期 | 2026-07-12 ~ 07-15 | Reasonix | DeepSeek Flash |
| 中期 | 2026-07-16 ~ 至今 | Claude Code | DeepSeek Pro |

> 模型推理通过 DeepSeek API (`api.deepseek.com`)，配置见 `reasonix.toml`。

## 支持的 LLM 提供商 (12)

| 服务商 | Endpoint | 默认模型 |
|--------|----------|---------|
| OpenAI | api.openai.com | gpt-4o |
| DeepSeek | api.deepseek.com | deepseek-chat |
| Kimi (月之暗面) | api.moonshot.cn | moonshot-v1-8k |
| GLM (智谱) | open.bigmodel.cn | glm-4-plus |
| Qwen (通义千问) | dashscope.aliyuncs.com | qwen-plus |
| Grok (xAI) | api.x.ai | grok-2 |
| 火山引擎 | ark.cn-beijing.volces.com | 豆包 |
| OpenModel | 自定义 | 自定义 |
| Self-Hosted | 自定义 | 自定义 |
| Custom | 自定义 | 自定义 |

## 许可证

GNU Affero General Public License v3.0 (AGPL-3.0) — 详见 [LICENSE](LICENSE)

> **核心要求**：如果你修改了本软件并作为网络服务运行，必须公开你的修改版源代码。

## 贡献

参见 [CONTRIBUTING.md](CONTRIBUTING.md) — 环境搭建、代码规范、构建命令。
