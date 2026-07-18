# 致谢与灵感来源

MengPaw 的开发受益于以下开源项目和框架的启发。

## 直接灵感来源

| 项目 | 借鉴内容 | 位置 |
|------|---------|------|
| **QwenPaw** (通义千问 Agent) | Skill Scanner 安全规则体系 — Prompt Injection 中英双语检测、ToolGuard 命令过滤、default_policy 分层安全策略；梦境模式(Dream Mode)记忆整理机制 | `core/security/SecurityPolicy.kt`, `core/security/Sanitizer.kt`, `core/agent/DreamEngine.kt` |
| **Hermes** (多Agent协作框架) | 多Agent角色委派、inbox任务分发、团队共享记忆 | `plugins/plugin-hermes/`, `core/acp/` |
| **OpenClaw** | Agent桌面自动化、跨设备任务委派概念 | `plugins/plugin-workflow/`, ACP 设备通信 |
| **Claude Code** (Anthropic) | MCP 协议双向通信、Sub-agent 模式、Prompt 安全审计日志 | `core/mcp/McpServer.kt`, `core/mcp/McpClient.kt` |
| **ReAct** (Google) | Thought → Action → Observation 循环推理模式 | `core/llm/PromptEngine.kt`, `core/AgentEngine.kt` |

## 参考项目

| 项目 | 参考方向 |
|------|---------|
| **Tavily** | AI 优化搜索引擎 API — Agent 搜索能力 |
| **ComfyUI** | 节点式工作流编排 — 图像生成管线 |
| **LangChain** | Tool/Chain/Agent 抽象模式 |
| **CrewAI** | 多Agent角色协作 |
| **Dify** | 可视化工作流编排 |
| **Arco Design** (字节跳动) | 设计系统色彩/间距/排版令牌 |
| **Material Design 3** (Google) | WindowSizeClass 响应式布局、Tonal Surface 层级、动态配色 |
| **Replicate** | 云端模型推理 API — 生图后端 |
| **Stability AI** | Stable Diffusion API |
| **OpenAI** | GPT-4o/Realtime/DALL-E API |
| **DeepSeek** | Prefix Cache 优化、DeepSeek API |
| **Kimi** (月之暗面) | Moonshot API |
| **GLM** (智谱) | ChatGLM API |
| **Qwen** (通义千问) | Qwen API |

## 安全规则层级（'核心/插件' 归属）

| 规则类别 | 归属 | 原因 |
|---------|------|------|
| Shell 命令拦截 (rm/mkfs/dd/reboot/...) | **core** `SecurityPolicy` | 所有插件共享的底线安全 |
| Prompt Injection 检测 (中英双语) | **core** `Sanitizer` | 所有 LLM 通信必经之路 |
| 控制字符/Unicode 注入 | **core** `Sanitizer` | 输入层通用防护 |
| API Key 脱敏 (AWS/Stripe/GitHub/...) | **core** `Sanitizer` | 所有日志输出 |
| File 扩展名分类 (inert/code/archive) | **plugin-skill** | Skill 扫描专用 |
| ToolGuard 命令审查 (systemctl/crontab/...) | **core** `SecurityPolicy` | 跨插件命令安全 |
| 供应链检测 (隐藏代码文件) | **plugin-skill** | Skill 安全审核 |
| ACP 来宾隔离 (PromptFirewall) | **core** `PromptFirewall` | 设备间通信安全 |
| 审计日志 (agent.audit) | **core** `Pipeline` | 所有命令执行追溯 |
| 速率限制 (30 cmd/s) | **core** `Pipeline` | DoS 防护 |

## 许可证

MengPaw: AGPL-3.0

上述所有参考项目归各自原作者所有。
