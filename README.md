# MengPaw 🐾

面向 Agent 的自举式 Android 操作系统框架。

> **Agent 通过内置 CLI 操控自身，API Key 是唯一安全禁区。**

## 快速开始

```bash
git clone https://github.com/mengpaw/mengpaw.git
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
└── plugins/                    # 23 个功能插件 (同级，均只依赖 kernel)
    ├── plugin-fs/              # 文件系统 (10 命令)
    ├── plugin-net/             # HTTP 网络 (3 命令)
    ├── plugin-memory/          # 记忆系统 (6 命令) ⭐
    ├── plugin-skill/           # 技能系统 (4 命令) ⭐
    ├── plugin-self/            # Agent 自省 (4 命令)
    ├── plugin-clipboard/       # 剪贴板 (3 命令)
    ├── plugin-notification/    # 通知管理 (3 命令)

    ├── plugin-dev/             # 插件开发工具 ⭐
    ├── plugin-tavily/          # AI 搜索
    ├── plugin-hermes/          # 多智能体协作
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

> ⭐ = 捆绑在 Shell APK 中

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
│  23 插件 (同级 · 只依赖 kernel)  │  ← 插件层
└────────────────────────────────┘
```

## 核心概念

### Agent ReAct 循环

```
Thought（思考） → Action（行动） → Observation（观察） → ... → Final Answer
```

Agent 通过 CLI 命令操控设备：
- `fs.cat /path/to/file` — 读取文件
- `net.curl https://api.example.com` — HTTP 请求
- `memory.write "内容"` — 写入记忆
- `skill.run 搜索` — 运行技能
- `sys.battery` — 系统信息
- `self.acp discover` — 发现对等设备

### 支持的 LLM 提供商 (12)

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

## 构建要求

- Android SDK 35
- JDK 17
- Gradle 8.12 (Wrapper 已包含)

## 测试

```bash
./gradlew :mengpaw-kernel:test   # 微内核 JVM 测试 (秒级, 无需模拟器)
```

## 开发工具

本项目由 AI 辅助开发，不同阶段使用的工具链：

| 阶段 | 时间 | 编排工具 | 主力模型 | 说明 |
|------|------|---------|---------|------|
| 早期 | 2026-07-12 ~ 07-15 | [Reasonix](https://github.com/reasonix-com/reasonix) | DeepSeek Flash | 基础架构搭建、品牌重塑、CLI 系统 |
| 中期 | 2026-07-16 ~ 至今 | [Claude Code](https://claude.ai/code) | DeepSeek Pro | 三审三校、架构重构、微内核拆分、安全加固 |

> 模型推理通过 DeepSeek API (`api.deepseek.com`)，配置见 `reasonix.toml`。

## 许可证

GNU Affero General Public License v3.0 (AGPL-3.0) — 详见 [LICENSE](LICENSE)

> **核心要求**：如果你修改了本软件并作为网络服务运行，必须公开你的修改版源代码。
