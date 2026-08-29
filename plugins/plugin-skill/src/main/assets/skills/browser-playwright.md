---
name: browser-playwright
description: Playwright 能力对照 — MengPaw 浏览器 page.* 命令与 Playwright API 的映射说明。触发词：「playwright 对照」「浏览器能力映射」「半自动武器」
enabled: true
category: browser
source: core
---
# Playwright 能力对照 (page.* 命令面, v0.9.0 am 桥单通道)

> MengPaw 浏览器命令面对齐 Playwright 语义（LLM 零学习成本）。调用通道: **am 桥单通道**
> （shell 子进程, 白名单 page.*/browser.*）。9880 HTTP 桥与 MCP 工具已退役 (v0.9.0)。
> 用法完整版: `skill.run browser-control`。

## 映射表

| Playwright API | MengPaw page.* | 说明 |
|----------------|----------------|------|
| `page.goto(url)` | `page.goto <url> [--wait domcontentloaded\|networkidle]` | 导航 + 精确等待 onPageFinished |
| `page.goto + page.screenshot()` | `page.load <url> [--max-height N]` | **半自动合体**: 导航 + 全页分段截图 + 坐标系统 |
| `page.screenshot(fullPage)` | `page.screenshot [--full] [--view]` | 全页(超长按段)/视口; 只回路径 + 尺寸/坐标 |
| `page.screenshot(element)` | `page.screenshot.element <css>` | 元素截图 |
| `page.click(sel)` | `page.click <css>` | 选择器点击 |
| `mouse.click(x, y)` | `page.click <seg> <x> <y>` | 按段坐标点击（分段截图坐标, 框架自动还原页面坐标） |
| `page.fill(sel, text)` | `page.fill <css> <text>` | 输入 |
| `page.selectOption()` | `page.select <css> <value>` | 下拉选值 |
| `page.locator(sel).textContent()` | `page.text <css>` | 元素文本 |
| `locator.getAttribute()` | `page.attr <css> <name>` | 元素属性 |
| `page.waitForSelector()` | `page.wait_selector <css> [--timeout N]` | 轮询等待元素出现 |
| `page.evaluate(js)` | `page.eval <js>` | 执行 JS |
| `page.url() / page.title()` | `page.url` / `page.title` | 当前页信息 |
| `page.goBack() / goForward()` | `page.back` / `page.forward` | 历史导航 |
| `page.keyboard.press()` | `page.key <key>` | 按键 (Enter/Tab/ArrowDown/单字符) |
| `page.mouse.wheel()` | `page.scroll_by <dy>` | 相对滚动 |
| `page.locator.scrollIntoView()` | `page.scroll <x> <y>` | 绝对滚动 |
| `page.content()` | `page.content [--grep P] [--regex] [-i] [--head N] [--tail N]` | 正文 + 内置过滤 (参照 fs.grep) |
| `locator.check()/uncheck()` | `page.check` / `page.uncheck` | 勾选/取消 |
| `page.locator('form').evaluate(f=>f.submit())` | `page.submit <css>` | 提交表单 |

## 差异 (vs Playwright)

- **超长页分段坐标**: `page.screenshot --full` / `page.load` 超长页截断分多段发送
  (每段 ≈ 视口高, 段数上限 30, `partial:true` 标注截断), 点击用
  `page.click <seg> <x> <y>`; 单张图时 `<x> <y>` 即可 (段号默认 1)
- **坐标还原**: 段图坐标 → 页面坐标由浏览器自动换算 (缩放比/段偏移), Agent 直接用图坐标
- **截图只回路径**: 落盘公共目录 `/storage/emulated/0/MengPaw/截图存档` (需「所有文件访问」
  授权, 首启弹窗; 拒绝后每次 page.load 提示重授), Agent 用 `agent.read` 看图
- **等待语义**: `--wait networkidle` 为近似实现 (加载完成后 300ms 无新活动), 非精确网络空闲
- **选择器仅 CSS**: 无 XPath/Playwright 专属语法

## 半自动循环 (推荐)

```
page.load https://example.com            # 一次完成: 导航 + 分段截图 + 坐标系统
page.click 1 320 480                      # 看图 → 按段图坐标点击 (段 1)
page.scroll_by 800                        # 滚动后再次 page.screenshot --full 核对
page.content --grep "价格" --head 20      # 过滤提取正文, 不进上下文
```

## 迁移示例 (Playwright → MengPaw)

Playwright:
```python
page.goto("https://example.com")
page.fill("#q", "mengpaw")
page.click("button")
print(page.title())
```

MengPaw (am 桥, shell 子进程):
```
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.goto https://example.com"
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.fill #q mengpaw"
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.click button"
am startservice -n com.mengpaw.browser/.service.RunCommandService \
  --es com.mengpaw.browser.RUN_COMMAND_ARGUMENTS "-c,page.title"
```
