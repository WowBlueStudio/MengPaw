---
name: browser-control
description: 浏览器协作完整手册 — 唤醒打开 (sys.browser.open)、半自动武器 page.* 命令面 (am 桥)、网页转档 (search.*)。触发词：「浏览器怎么用」「浏览器手册」「唤醒浏览器」「半自动」
enabled: true
category: browser
source: core
---
# 浏览器协作完整手册 (v0.9.0 am 桥单通道)

> 浏览器 (MP Browser) 是独立 APK。Agent 经 **am 桥单通道**直接调用浏览器命令面
> (page.* / browser.*, 白名单)。9880 HTTP 桥与 MCP 开放模式已退役 (决策 #7),
> 仅同签名 Shell 可经 am 桥调用, 第三方接入不再支持。

## 快速上手

```
1. 唤醒并打开:  sys.browser.open https://example.com
2. 半自动加载:  am startservice -n com.mengpaw.browser/.service.RunCommandService --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.load https://example.com"
3. 看图操作:    page.click 1 <x> <y> / page.scroll_by <dy> (命令串同上, 换 page.click 等)
4. 过滤提取:    am 桥 page.content --grep "关键词" --head 20
5. 转档保存:    search.md https://example.com/article --name article_1
```

## 一、前台唤醒 (sys.browser.open)

| 命令 | 语法 | 说明 |
|------|------|------|
| `sys.browser.open` | `sys.browser.open [url]` | 唤起 MP 浏览器到前台; 带 url 同时打开 |

**说明**: 唤起浏览器后 am 桥可用 (浏览器 onCreate 初始化 `BuiltinBrowserPlugin.shared`)。
浏览器未安装时 `sys.browser.open` 明确报错。

## 二、半自动武器命令面 (page.*, 推荐)

命令名/参数对齐 Playwright (LLM 零学习成本)。调用通道 **am 桥单通道**:

```
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,<page.*|browser.* 命令串>" \
  [--es com.mengpaw.browser.RUN_COMMAND_OUTPUT <输出路径>]
```

**半自动合体**: `page.load <url> [--max-height N]` — 导航 + 精确等待 + 全页分段截图 + 坐标系统
(超长页按段返回, partial:true 标注截断; 截图落公共目录 `/storage/emulated/0/MengPaw/截图存档`,
Agent 用 `agent.read` 看图)

**导航/截图**: `page.goto <url> [--wait domcontentloaded|networkidle]` |
`page.screenshot [--full] [--view]` | `page.screenshot.element <css>`

**交互**: `page.click <seg> <x> <y>` (坐标, 单图段号默认 1) | `page.click <css>` (选择器) |
`page.fill <css> <text>` | `page.select <css> <value>` | `page.submit <css>` |
`page.check` | `page.uncheck` | `page.key <key>`

**查询**: `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` |
`page.text <css>` | `page.attr <css> <name>` | `page.wait_selector <css> [--timeout N]`

**滚动/JS/信息**: `page.scroll <x> <y>` | `page.scroll_by <dy>` | `page.eval <js>` |
`page.url` | `page.title` | `page.back` | `page.forward`

**保留的 browser.\***: 标签页 (`tabs/tab/tab.open/tab.close/tab.all`)、
效率 (`inject/diff/preload`)、存储/Cookie (`storage/cookies 系`)、
设置/查询 (`viewport/userAgent/version/visible/enabled`)、等待/对话框 (`wait/wait.nav/dialog.*`)。

> **9880 HTTP 桥与开放模式已退役 (v0.9.0)**: 不再有 `browser.mcp.*` 工具
> (browser_navigate/browser_screenshot/browser_click 等) 与 `/mcp` `/health` HTTP 端点。
> 浏览器控制统一走 am 桥单通道。旧命令 `batch/q` 已移除 (browser.* 23→21)。

## 三、网页转档 (search.*)

不依赖浏览器在线 — Agent 直接抓取转换:

| 命令 | 语法 | 说明 |
|------|------|------|
| `search.md` | `search.md <url\|路径> [--name x]` | 抓取 + 转 Markdown 存 SEARCH_OUTPUTS (一步到位) |
| `search.clean` | `search.clean <url\|路径> [--save]` | 提取正文去噪 |
| `search.outputs` | `search.outputs [--all]` | 列出已转换文档 |
| `search.clear` | `search.clear [--all] [--older-than N]` | 清理输出 |

**浏览器提炼闭环**: 浏览器菜单「提炼网页要点」→ Agent 收到任务 → search.md 转换 → LLM 提炼要点 → 写回传文件 → Shell 自动回传浏览器预览。

## 四、浏览器扩展 (v0.9.0 am 桥单通道)

> 浏览器进程内插件注册机制 (BrowserPlugin / BrowserPluginRegistry) 已删除 (P2 死代码清理)。
> 浏览器能力 = 内置命令面 `page.*` (22 条) + `browser.*` (21 条, BuiltinBrowserPlugin 合流),
> 经 **am 桥单通道** (signature 白名单) 暴露。需要新浏览器能力时, 在浏览器侧扩展
> `BuiltinBrowserPlugin` 命令或 `RunCommandService` 即可。

## 常见问题

- **am 桥报"浏览器未就绪"** → 浏览器未运行, 先 `sys.browser.open <url>` 再调
- **page.load 提示存储权限** → 未授予「所有文件访问」, 浏览器首启弹窗引导或系统设置手动授权
- **page.click 错位** → 确认用的是最近一次 page.screenshot --full / page.load 的段图坐标
- **页面提取为空** → 页面 JS 渲染, 先 page.goto 等加载完成再 page.content
