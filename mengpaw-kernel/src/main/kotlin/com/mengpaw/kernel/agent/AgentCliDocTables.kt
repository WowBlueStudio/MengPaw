// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * CLI.md 生成用命令描述表 — 拆自 AgentDocManager companion (400 行文件拆分)。
 * AgentDocManager companion 保留同名声明的委托, 外部引用 (IndexCoverageTest /
 * AgentExecutor.browserTools) 不受影响。
 */
internal object AgentCliDocTables {
    /** Built-in self.* commands for CLI.md generation. */
    val SELF_COMMANDS = listOf(
        Triple("status", "self status", "Agent 运行状态"),
        Triple("config", "self config [key=value]", "查看/设置配置"),
        Triple("stats", "self.stats [events [--tail N]]", "内存/CPU/线程 + token/耗时统计 + 事件流"),
        Triple("version", "self version", "版本信息"),
        Triple("avatar", "self.avatar [name|file]", "切换头像"),
        Triple("theme", "self.theme [light|dark|system]", "切换主题配色"),
        Triple("mcp", "self.mcp", "MCP 协议配置/服务列表"),
        Triple("trigger", "self.trigger [ls|add|rm]", "触发器管理 (Cron/Lifetime)"),
        Triple("acp", "self.acp", "ACP 框架通讯状态/配对设备"),
        Triple("tools", "self.tools [namespace]", "列出可用命令 (完整遍历)"),
        Triple("ports", "self.ports [--json]", "端口/网络接口一览"),
        Triple("search", "self.search <描述> [--top N]", "自然语言搜索命令 (BM25)"),
        Triple("search.stats", "self.search.stats", "搜索索引统计"),
        Triple("time", "self.time [format]", "当前日期时间"),
        Triple("notify.message", "self.notify.message <text>", "推送消息给用户"),
        Triple("notify.banner", "self.notify.banner <text> [--level]", "顶部横幅通知")
    )

    /** Built-in plugin.* commands. */
    val PLUGIN_COMMANDS = listOf(
        Triple("marketplace", "plugin.marketplace [--refresh]", "拉取插件市场索引"),
        Triple("search", "plugin.search <query>", "搜索插件"),
        Triple("install", "plugin.install <id>", "安装插件"),
        Triple("uninstall", "plugin.uninstall <id>", "卸载插件"),
        Triple("list", "plugin.list", "列出已安装插件"),
        Triple("info", "plugin.info <id>", "插件详情"),
        Triple("enable", "plugin.enable <id>", "启用插件"),
        Triple("disable", "plugin.disable <id>", "禁用插件"),
        Triple("update", "plugin.update <id>", "检查插件更新"),
        Triple("upgrade", "plugin.upgrade --all", "升级全部插件"),
        Triple("verify", "plugin.verify <id> | plugin.verify --all", "校验插件文件完整性"),
        Triple("auto", "plugin.auto <wake|sleep|status|sleep-idle>", "插件省电管理")
    )

    /** Built-in agent.* commands. */
    val AGENT_COMMANDS = listOf(
        Triple("docs", "agent.docs", "列出所有文档"),
        Triple("cli", "agent.cli", "查看 CLI 命令参考"),
        Triple("modes", "agent.modes", "斜杠命令模式菜单 (modes.md)"),
        Triple("boost", "agent.boost", "阅读初始化引导 (新 Agent 第一步)"),
        Triple("boost.delete", "agent.boost.delete", "删除引导加速文件"),
        Triple("profile", "agent.profile", "查看身份档案"),
        Triple("soul", "agent.soul", "查看灵魂设定"),
        Triple("audit", "agent.audit [条数]", "命令审计日志"),
        Triple("browser-tools", "agent.browser-tools", "MP浏览器扩展能力"),
        Triple("dream", "agent.dream", "梦境整理 (中期→洞察→长期)"),
        Triple("cleanup", "agent.cleanup", "清理过期文件"),
        Triple("storage", "agent.storage", "存储占用/限额"),
        Triple("sessions", "agent.sessions [keyword]", "搜索历史会话"),
        Triple("session.delete", "agent.session.delete <id>", "删除历史会话"),
        Triple("session.archive", "agent.session.archive <id>", "归档会话"),
        Triple("session.current", "agent.session.current", "当前会话状态"),
        Triple("read", "agent.read <路径>", "读取工作区文件 (只读)"),
        Triple("write", "agent.write <路径> <内容> | agent.write <路径> --from <源文件>", "写入工作区文件 (原子; --from 从文件导入多行内容)"),
        Triple("policy", "agent.policy [allow|deny <前缀> [--to <agent>]]", "命令前缀级授权 (per-agent, 多Agent隔离)"),
        Triple("ls", "agent.ls [路径]", "列出工作区目录"),
        Triple("rm", "agent.rm <路径>", "删除工作区文件"),
        Triple("mkdir", "agent.mkdir <路径>", "创建工作区目录"),
        Triple("output", "agent.output", "输出目录管理 (HTML/MD/PDF)"),
        Triple("memory", "agent.memory [关键词]", "记忆索引/搜索"),
        Triple("memory.record", "agent.memory.record <内容>", "写中期记忆"),
        Triple("memory.keep", "agent.memory.keep <内容>", "写长期记忆"),
        Triple("memory.read", "agent.memory.read <id>", "按 ID 读一条 (三轨)"),
        Triple("memory.search", "agent.memory.search <关键词> [--track long|mid|project]", "跨轨搜索记忆"),
        Triple("memory.stats", "agent.memory.stats", "记忆统计"),
        Triple("memory.write", "agent.memory.write <id> <内容>", "按 ID 写长期 (存在则更新)"),
        Triple("memory.mid", "agent.memory.mid [日期]", "查看中期记忆"),
        Triple("memory.project", "agent.memory.project [项目名]", "查看项目记忆"),
        Triple("memory.project.save", "agent.memory.project.save <项目名> <内容>", "项目经验总结"),
        Triple("memory.project.delete", "agent.memory.project.delete <项目名>", "删除项目分片"),
        Triple("memory.mid.delete", "agent.memory.mid.delete <日期>", "删除中期分片"),
        Triple("memory.rm", "agent.memory.rm <时间戳>", "删长期条目"),
        Triple("memory.edit", "agent.memory.edit <时间戳> <内容>", "改长期条目"),
        Triple("memory.mid.rm", "agent.memory.mid.rm <日期> <时间戳>", "删中期条目"),
        Triple("memory.mid.edit", "agent.memory.mid.edit <日期> <时间戳> <内容>", "改中期条目"),
        Triple("memory.project.rm", "agent.memory.project.rm <项目名> <时间戳>", "删项目条目"),
        Triple("memory.project.edit", "agent.memory.project.edit <项目名> <时间戳> <内容>", "改项目条目")
    )

    /** Built-in security.* commands (v0.34.1 — 攻击来源黑名单). */
    val SECURITY_COMMANDS = listOf(
        Triple("block", "security.block <来源>", "将攻击来源 (域名/路径) 加入黑名单, 后续同来源内容直接阻止"),
        Triple("unblock", "security.unblock <来源>", "从黑名单移除来源"),
        Triple("blocklist", "security.blocklist", "列出全部黑名单条目")
    )

    /** 浏览器协作能力 — readable by Agent via CLI (v0.22.1 重写: 真实三通道, 移除未接线的 45 命令手册). */
    val BROWSER_TOOLS_MD = """
# MP浏览器 协作能力 (v0.22.1)

> 浏览器是独立 APK。Agent 可用的三通道: 唤醒打开 / MCP 工具 / 网页转档。
> 完整手册: `skill.run browser-control`。

## 1. 前台唤醒与打开
- `sys.browser.open [url]` — 唤起 MP 浏览器到前台; 带 url 同时打开。唤起后 MCP 桥自动启动。

## 2. 浏览器 MCP 工具 (设备内 HTTP 桥 127.0.0.1:9880, 打开浏览器即自动启用)
- `browser.mcp.status` — 检查桥在线/离线
- `browser.mcp.tools` — 列出 6 个工具及参数
- `browser.mcp.invoke <工具> <JSON参数>` — 调用:
  - `browser_navigate` {"url": "..."} — 导航
  - `browser_screenshot` {} — 当前页截图
  - `browser_click` {"selector": "css"} — 点击元素
  - `browser_type` {"selector": "css", "text": "..."} — 输入文本
  - `browser_extract` {} — 提取页面结构 (标题/链接/表单/文本)
  - `browser_eval` {"script": "js"} — 执行任意 JS

## 3. 网页转档与提炼 (不依赖浏览器在线)
- `search.md <url> [--name x]` — 抓取转 Markdown 存 SEARCH_OUTPUTS
- `search.clean <url|路径> [--save]` — 提取正文去噪
- `search.outputs` / `search.clear` — 输出管理
- 浏览器菜单「提炼网页要点」→ Agent 处理 → 自动回传浏览器预览

## 浏览器扩展 (2026-08-06: BrowserPluginRegistry 死代码已删除)
- 浏览器进程内插件注册机制 (BrowserPlugin/BrowserPluginRegistry) 已移除 — register() 零调用, 插件与浏览器跨进程不可达
- 浏览器能力统一经 9880 MCP 桥: `browser.mcp.tools` 列全部工具 + 内置 browser.* 命令 (44 条)

## Agent Skills
- `skill.run browser-control` — 完整协作手册
""".trimIndent()
}
