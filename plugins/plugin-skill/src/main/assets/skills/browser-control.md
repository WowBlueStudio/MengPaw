---
name: browser-control
description: 浏览器协作完整手册 — 唤醒打开 (sys.browser.open)、MCP 工具 (browser.mcp.*)、网页转档 (search.*)、插件开发 API。触发词：「浏览器怎么用」「浏览器手册」「唤醒浏览器」
enabled: true
category: browser
---
# 浏览器协作完整手册

> 浏览器 (MP Browser) 是独立 APK。Agent 通过三个通道协作, 不能直接执行浏览器内部命令。

## 快速上手

```
1. 唤醒并打开:  sys.browser.open https://example.com
2. 检查桥在线:  browser.mcp.status
3. 提取内容:    browser.mcp.invoke browser_extract {}
4. 导航:        browser.mcp.invoke browser_navigate {"url":"https://www.baidu.com/s?wd=天气"}
5. 点击/输入:   browser.mcp.invoke browser_click {"selector":"#btn"}
               browser.mcp.invoke browser_type {"selector":"#q","text":"MengPaw"}
6. 执行脚本:    browser.mcp.invoke browser_eval {"script":"document.title"}
7. 转档保存:    search.md https://example.com/article --name article_1
```

## 一、前台唤醒 (sys.browser.open)

| 命令 | 语法 | 说明 |
|------|------|------|
| `sys.browser.open` | `sys.browser.open [url]` | 唤起 MP 浏览器到前台; 带 url 同时打开 |
| `browser.mcp.status` | `browser.mcp.status` | 检查 MCP 桥在线/离线 (浏览器未运行时报离线) |

**说明**: 唤起浏览器后 MCP 桥自动启动 (浏览器 onCreate), 无需手动启用。
浏览器未安装时 `sys.browser.open` 明确报错。

## 二、MCP 工具 (browser.mcp.*)

设备内 HTTP 桥: `127.0.0.1:9880` (仅回环, 不暴露网络)。`browser.mcp.tools` 查看实时工具列表。

### 6 个工具

| 工具 | 参数 | 说明 |
|------|------|------|
| `browser_navigate` | `{"url": "..."}` | 导航到 URL (等待加载完成, 最坏 10s) |
| `browser_screenshot` | `{}` | 当前页截图 |
| `browser_click` | `{"selector": "css"}` | 点击匹配元素 |
| `browser_type` | `{"selector": "css", "text": "..."}` | 向输入框输入文本 |
| `browser_extract` | `{}` | 提取页面结构 (标题/链接/表单/文本) |
| `browser_eval` | `{"script": "js"}` | 执行任意 JavaScript, 返回 JSON 结果 |

### 调用方式

```
browser.mcp.invoke browser_navigate {"url":"https://example.com"}
browser.mcp.invoke browser_extract {}
browser.mcp.invoke browser_eval {"script":"document.querySelector('h1').textContent"}
```

返回 JSON: `{"ok":true,...}` 或 `{"ok":false,"error":"..."}`。

## 三、网页转档 (search.*)

不依赖浏览器在线 — Agent 直接抓取转换:

| 命令 | 语法 | 说明 |
|------|------|------|
| `search.md` | `search.md <url\|路径> [--name x]` | 抓取 + 转 Markdown 存 SEARCH_OUTPUTS (一步到位) |
| `search.clean` | `search.clean <url\|路径> [--save]` | 提取正文去噪 |
| `search.outputs` | `search.outputs [--all]` | 列出已转换文档 |
| `search.clear` | `search.clear [--all] [--older-than N]` | 清理输出 |

**浏览器提炼闭环**: 浏览器菜单「提炼网页要点」→ Agent 收到任务 → search.md 转换 → LLM 提炼要点 → 写回传文件 → Shell 自动回传浏览器预览。

## 四、浏览器插件开发 (Browser Plugin API, 面向插件作者)

浏览器进程内扩展钩子 (浏览器 APK 内置 BrowserPluginRegistry):

| 钩子 | 触发时机 | 用途 |
|------|---------|------|
| onPageStarted(url) | 页面开始加载 | 监听导航事件 |
| onPageFinished(url, title) | 页面加载完成 | 注入 JS/CSS |
| shouldIntercept(request) | 每个资源请求 | 广告拦截、请求修改 |
| injectScript(url) | 每页加载后 | Tampermonkey 风格用户脚本 |
| injectStyle(url) | 每页加载后 | 暗黑模式、自定义样式 |
| menuItems() | 浏览器菜单打开时 | 添加自定义菜单项 |
| onLongPress(element) | 长按页面元素 | 图片/视频/二维码处理 |

流程: 实现 `BrowserPlugin` 接口 → `BrowserPluginRegistry.register(plugin)` → 打包发布。
已安装浏览器插件: `plugin.list` 查看 browser- 前缀。

## 常见问题

- **browser.mcp.status 离线** → 浏览器未运行, 先 `sys.browser.open`
- **浏览器没反应** → 桥仅 127.0.0.1, 检查是否被杀 (重新唤起)
- **页面提取为空** → 页面 JS 渲染, 先 browser_navigate 等加载完成再 extract
