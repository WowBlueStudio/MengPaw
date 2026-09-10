---
name: source-index
description: 回答技术问题时快速定位要读的文档和源码；减少盲目搜索。触发词：「源码在哪」「读哪个文档」「定位文档」
enabled: true
category: system
source: core
---
# 文档与源码速查

当 Agent 需要回答 **架构、原理、代码实现** 类问题时，按关键词快速定位要读的文件。

## 使用步骤

1. 从用户问题中提取关键词（对照下表）
2. 用 `agent.memory search <关键词>` 查阅对应文档
3. 仍不足时，用 Linux 命令通道读对应源码（`ls` / `cat <路径>` / `grep`，内置，不需插件）

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
| 浏览器、WebView | — | 已拆独立仓库 MengPaw-Browser（本仓库无此路径） |
| 技能、skill、剧本 | agent.memory | `plugins/plugin-skill/.../SkillPlugin.kt` |
| 文件、目录、读写、ls、cat | — | `mengpaw-kernel/.../cli/LinuxCommandExecutor.kt` |
| 网络、HTTP、curl | agent.memory | `plugins/plugin-net/.../NetPlugin.kt` |
| 记忆、memory、存储 | agent.memory | `mengpaw-kernel/.../agent/AgentDocs.kt` + `AgentExecutor.kt` |
| 翻译、translate、语言 | — | `mengpaw-kernel/.../llm/TranslateMiddleware.kt` |

## 模块速查

> 文件数为 2026-09-10 快照（`src/main/kotlin` 下 `.kt` 计数），仅供量级参考。

| 模块 | 位置 | 文件数 | 职责 |
|------|------|--------|------|
| kernel | `mengpaw-kernel/src/.../kernel/` | 154 | 微内核（CLI/LLM/安全/会话/插件框架） |
| core | `mengpaw-core/src/.../core/` | 35 | Android 适配（Vault/IntegrityGuard/SysExecutor） |
| shell | `mengpaw-shell/src/.../shell/` | 123 | 主应用（Chat UI/设置/服务） |
| browser | 独立仓库 MengPaw-Browser | — | 独立浏览器（本仓库不含） |
| design | `mengpaw-design-system/src/.../design/` | 8 | Arco 主题/Markdown 渲染 |

## 约定

- 先读文档（`agent.memory search`），再读源码（Linux 命令通道 `cat`/`grep`，内置）
- `agent.cli` 返回完整 CLI 参考
- `agent.memory search <关键词>` 全文搜索所有记忆文档
- 不确定时先 `ls` 看看当前有什么，不要盲目猜测路径
- 文件读写用 Linux 命令通道（`cat` / `sed` / 重定向），如需批量网络请求用 net 插件 `net.*` 命令
