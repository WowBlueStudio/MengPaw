// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * 浏览器协作能力文档 (v0.34.3: 命令描述表随 CLI.md 移除, 本对象仅存 BROWSER_TOOLS_MD)。
 */
internal object AgentCliDocTables {
    // v0.34.3: SELF/PLUGIN/AGENT/SECURITY 命令表删除 (P2-8 单一数据源),
    // CLI.md 工作区文档整体移除 — 命令发现走 self.tools/self.search。

    /** 浏览器协作能力 — readable by Agent via CLI (v0.22.1 重写; v0.8.0 更新: page.* 命令面 + am 桥). */
    val BROWSER_TOOLS_MD = """
# MP浏览器 协作能力 (v0.8.0)

> 浏览器是独立 APK。Agent 可用通道: 唤醒打开 / am 桥 (shell 子进程) / 网页转档。
> 完整手册: `skill.run browser-control`。

## 1. 前台唤醒与打开
- `sys.browser.open [url]` — 唤起 MP 浏览器到前台; 带 url 同时打开。

## 2. 浏览器半自动武器 (Playwright 语义, 决策 2026-08-11)
- `page.load <url> [--max-height N]` — **推荐**: 导航 + 精确等待 + 自动全页分段截图 + 坐标系统
  (超长页按段返回, partial:true 标注截断; 存储权限未授予时提示重授)
- `page.goto <url> [--wait domcontentloaded|networkidle]` — 导航 + 精确等待
- `page.screenshot [--full] [--view]` — 全页(分段)/视口截图, 只回路径 + 尺寸/坐标
- `page.click <seg> <x> <y>` | `page.click <css>` — 按段坐标点击或选择器点击
- `page.fill <css> <text>` / `page.select/submit/check/uncheck` — 表单操作
- `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` — 正文 + 内置过滤
- `page.text <css>` / `page.attr <css> <name>` / `page.wait_selector <css> [--timeout N]`
- `page.scroll <x> <y>` / `page.scroll_by <dy>` / `page.eval <js>`
- `page.url` / `page.title` / `page.back` / `page.forward` / `page.key <key>`
- `page.screenshot.element <css>` — 元素截图

## 3. 标签页/设置 (browser.*, page.* 不覆盖的保留命令)
- `browser.tabs / tab / tab.open / tab.close / tab.all` — 多标签管理
- `browser.batch` / `browser.q` — 批量执行/快捷选择器
- `browser.inject / diff / preload` — 性能优化
- `browser.storage` / `browser.cookies` 系 / `browser.viewport` / `browser.userAgent` / `browser.version`
- `browser.visible / enabled` / `browser.wait` / `browser.wait.nav` / `browser.dialog.*`

## 4. 调用通道
- **am 桥 (shell 子进程)**: `am startservice -n com.mengpaw.browser/.service.RunCommandService
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,<page.*|browser.* 命令串>"
  [--es com.mengpaw.browser.RUN_COMMAND_OUTPUT <输出路径>]` — signature 权限, 白名单只放行
  page.*/browser.* 命令 (CommandMonitor 校验), 输出可落盘公共目录后 cat 读回
- **9880 MCP 桥 (过渡)**: 浏览器打开自动启用, 供 browser-mcp-plugin 的 browser.mcp.* 调用;
  退役计划见方案文档 (Phase 2 验证后移除)

## 5. 网页转档与提炼 (不依赖浏览器在线)
- `search.md <url> [--name x]` — 抓取转 Markdown 存 SEARCH_OUTPUTS
- `search.clean <url|路径> [--save]` — 提取正文去噪
- `search.outputs` / `search.clear` — 输出管理
- 浏览器菜单「提炼网页要点」→ Agent 处理 → 自动回传浏览器预览

## Agent Skills
- `skill.run browser-control` — 完整协作手册 (含 browser-playwright 语义)
""".trimIndent()
}
