# 致谢

MengPaw 的开发受益于以下项目和框架。此处严格区分「代码参考」（有具体代码移植/适配）与「灵感来源」（概念/架构启发，无代码引用），以避免版权纠纷。

---

## 代码参考（Code References）

以下项目的具体代码模式、算法或实现被移植/适配到 MengPaw 中。所有引用均符合原项目的开源协议。

| 项目 | 许可证 | 引用内容 | MengPaw 位置 | 说明 |
|------|--------|---------|-------------|------|
| **Reasonix** | MIT | 上下文折叠阈值体系（50%/60%/80%/90% 四级）；陈旧工具结果裁剪；tokPerChar 动态校准；折叠经济性检查；卡死检测 | `AgentEngine.kt`, `LlmRequestBuilder.kt` | 参考 `internal/agent/compact.go` 和 `internal/agent/cache_shape.go`，移植为 Kotlin 实现。Copyright (c) 2026 Reasonix Contributors |
| **QwenPaw** | Apache 2.0 | Agent 文档模板体系（SOUL / BOOTSTRAP / MEMORY / PROFILE / AGENTS / HEARTBEAT 六文件结构） | `AgentDocs.kt` | 文件结构和引导流程参考 QwenPaw Desktop 的 `agents/md_files/zh/` 目录，内容已完全改写为 MengPaw 专属。Copyright 2025 The QwenPaw Authors |
| **ReAct** (Google) | Apache 2.0 | Thought → Action → Observation 循环推理模式 | `PromptEngine.kt`, `AgentEngine.kt` | 论文《ReAct: Synergizing Reasoning and Acting in Language Models》提出的范式 |

---

## 灵感来源（Inspiration）

以下项目的概念、架构或设计思想启发了 MengPaw 的设计，但**未直接使用其代码**。

| 项目 | 启发方向 | 体现位置 |
|------|---------|---------|
| **Claude Code** (Anthropic) | MCP 协议双向通信、Sub-agent 委托模式 | `core/mcp/`, ACP 框架设计 |
| **Hermes** | 多 Agent 角色委派、inbox 任务分发 | `plugins/plugin-hermes/` |
| **Arco Design** (字节跳动) | 设计系统色彩/间距/排版令牌体系 | `mengpaw-design-system/` |
| **Material Design 3** (Google) | WindowSizeClass 响应式布局、Tonal Surface 动态配色 | `AdaptiveLayout.kt`, `ArcoTheme.kt` |
| **LangChain** | Tool / Chain / Agent 抽象模式 | `Pipeline.kt` 命令注册链 |
| **CrewAI** | 多 Agent 角色协作范式 | ACP 框架通讯录 |
| **Dify** | 可视化工作流编排概念 | `plugin-workflow` |
| **OpenClaw** | Agent 桌面自动化、跨设备任务委派 | `plugin-workflow` |
| **Tavily** | AI 优化搜索引擎 API | `plugin-tavily` |
| **ComfyUI** | 节点式工作流编排 | `plugin-comfy` |
| **Kuri** (MIT) | WebView @JavascriptInterface 双向桥、accessibility-tree 页面内容提取架构 | `mengpaw-browser/bridge/BrowserBridge.kt` |
| **native-devtools-mcp** (MIT) | MCP 协议暴露浏览器工具的设计思路 | `plugin-browser-mcp` (规划中) |
| **WebDroid Agent** (MIT) | WebView JS bridge 模式的 DOM 操控参考 | `BrowserScreen.kt` ShellBrowserBridge |

---

## API / 模型集成

以下为 MengPaw 集成的第三方 API 和模型服务（均为 API 调用，非代码引用）：

| 服务商 | 集成方式 |
|--------|---------|
| **OpenAI** (GPT-4o / GPT-4o-mini) | REST API |
| **DeepSeek** (V4 / Chat / Reasoner) | REST API |
| **Kimi / Moonshot** (月之暗面) | REST API |
| **GLM / 智谱** (ChatGLM) | REST API |
| **Qwen / 通义千问** (阿里云) | REST API |
| **Stability AI** (Stable Diffusion) | REST API |
| **Replicate** | REST API |

---

## 许可证

MengPaw: **AGPL-3.0-or-later**

Copyright (c) 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)

上述所有参考项目归各自原作者所有。引用的开源代码片段在原项目许可证下使用。
