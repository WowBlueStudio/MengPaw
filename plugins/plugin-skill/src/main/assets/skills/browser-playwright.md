---
name: browser-playwright
description: Playwright 能力对照 — MengPaw 浏览器 MCP 工具与 Playwright API 的映射说明。触发词：「playwright 对照」「浏览器能力映射」
enabled: true
category: browser
source: core
---
# Playwright 能力对照 (MCP 工具版)

> MengPaw 浏览器经 MCP 6 工具暴露能力 — 以下为与 Playwright API 的对照, 便于从 Playwright 迁移。

## 映射表

| Playwright API | MengPaw MCP 工具 | 说明 |
|----------------|------------------|------|
| `page.goto(url)` | `browser_navigate` | 导航 (等待加载) |
| `page.click(sel)` | `browser_click` | 点击 |
| `page.fill(sel, text)` | `browser_type` | 输入 (无聚焦要求, 直接注入) |
| `page.screenshot()` | `browser_screenshot` | 截图 |
| `page.evaluate(js)` | `browser_eval` | 执行 JS |
| `page.content()` | `browser_extract` | 结构化提取 (标题/链接/表单/文本) |
| `page.waitForSelector()` | navigate 内置等待 + eval 轮询 | 等待元素出现用 eval 循环探测 |
| `locator.count()` | `browser_eval` + querySelectorAll | 元素计数探测 |

## 差异 (vs Playwright)

- **无独立等待 API** — 加载等待内置于 `browser_navigate` (最坏 10s); 元素等待用 `browser_eval` 轮询
- **无多标签页控制** — 操作当前活动标签页
- **无 iframe 直通** — iframe 内元素需 `browser_eval` 进入 `contentDocument`
- **选择器仅 CSS** — 无 XPath/Playwright 专属语法
- **参数扁平化** — 工具参数是 `Map<String,String>`, 复杂值 (对象/数组) 用 JSON 字符串经 eval 处理

## 迁移示例

Playwright:
```python
page.goto("https://example.com")
page.fill("#q", "mengpaw")
page.click("button")
print(page.title())
```

MengPaw:
```
browser.mcp.invoke browser_navigate {"url":"https://example.com"}
browser.mcp.invoke browser_type {"selector":"#q","text":"mengpaw"}
browser.mcp.invoke browser_click {"selector":"button"}
browser.mcp.invoke browser_eval {"script":"document.title"}
```
