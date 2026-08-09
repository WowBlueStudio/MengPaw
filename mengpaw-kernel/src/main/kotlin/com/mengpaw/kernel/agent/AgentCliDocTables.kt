// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * CLI.md 生成用命令描述表 — 拆自 AgentDocManager companion (400 行文件拆分)。
 * AgentDocManager companion 保留同名声明的委托, 外部引用 (IndexCoverageTest /
 * AgentExecutor.browserTools) 不受影响。
 */
internal object AgentCliDocTables {
    // v0.34.3 P2-8: SELF/PLUGIN/AGENT/SECURITY 命令表删除 — CLI.md 改为从
    // CommandSearch (BuiltinCommandIndex 单一数据源) 动态生成, 消除双份手写表。

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
