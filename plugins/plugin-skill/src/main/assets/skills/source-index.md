---
name: source-index
description: 回答技术问题时快速定位要读的文档和源码；减少盲目搜索
enabled: true
category: system
---
# 文档与源码速查

当 Agent 需要回答 **架构、原理、代码实现** 类问题时，按关键词快速定位要读的文件。

## 使用步骤

1. 从用户问题中提取关键词（对照下表）
2. 用 `agent.memory [query]` 查阅对应文档
3. 仍不足时，用 `fs.cat` 或 `fs.ls` 读对应源码

## 关键词 → 文档与源码

| 关键词 | 文档/记忆 | 源码路径 |
|--------|----------|---------|
| CLI、命令、解析、执行 | CLI.md | `mengpaw-kernel/.../cli/CliInterpreter.kt` |
| LLM、模型、API、Provider | CLI.md | `mengpaw-kernel/.../llm/AdaptiveLlmProvider.kt` |
| 安全、权限、拦截、防火墙 | — | `mengpaw-kernel/.../security/SecurityPolicy.kt` |
| 插件、plugin、安装、市场 | CLI.md | `mengpaw-kernel/.../plugin/PluginExecutor.kt` |
| 会话、记忆、压缩、历史 | — | `mengpaw-kernel/.../session/SessionManager.kt` |
| MCP、工具、tool | — | `mengpaw-kernel/.../mcp/McpServer.kt` |
| ACP、设备通信、配对 | — | `mengpaw-kernel/.../acp/AcpServer.kt` |
| Agent、引擎、ReAct、循环 | — | `mengpaw-kernel/.../AgentEngine.kt` |
| UI、设置、主题、Compose | — | `mengpaw-shell/.../ui/screens/SettingsScreen.kt` |
| 浏览器、WebView | — | `mengpaw-browser/.../BrowserActivity.kt` |
| 技能、skill、剧本 | agent.memory | `plugins/plugin-skill/.../SkillPlugin.kt` |
| 文件系统、fs、读写 | agent.memory | `plugins/plugin-fs/.../FsPlugin.kt` |
| 网络、HTTP、curl | agent.memory | `plugins/plugin-net/.../NetPlugin.kt` |
| 记忆、memory、存储 | agent.memory | `plugins/plugin-memory/.../MemoryPlugin.kt` |
| 翻译、translate、语言 | — | `mengpaw-kernel/.../llm/TranslateMiddleware.kt` |

## 模块速查

| 模块 | 位置 | 文件数 | 职责 |
|------|------|--------|------|
| kernel | `mengpaw-kernel/src/.../kernel/` | 44 | 微内核（CLI/LLM/安全/会话/插件框架） |
| core | `mengpaw-core/src/.../core/` | 6 | Android 适配（Vault/IntegrityGuard/SysExecutor） |
| shell | `mengpaw-shell/src/.../shell/` | 21 | 主应用（Chat UI/设置/服务） |
| browser | `mengpaw-browser/src/.../browser/` | 5 | 独立浏览器 |
| design | `mengpaw-design-system/src/.../design/` | 5 | Arco 主题/Markdown 渲染 |

## 约定

- 先读文档（`agent.memory`），再读源码（`fs.cat`）
- `agent.cli` 返回完整 CLI 参考
- `agent.memory search <关键词>` 全文搜索所有记忆文档
- 不确定时先 `fs.ls` 看目录结构，不要盲目猜测路径
