---
name: browser-playwright
description: Playwright API — browser.* 命令映射表，附 Android WebView 差异说明
enabled: true
category: browser
---
# Playwright API 映射表

将 Playwright 标准 API 映射到 MengPaw `browser.*` 命令。左边是 Playwright 方法，右边是对应的 browser.* 命令。

## 核心映射

| Playwright 方法 | browser.* 命令 | 备注 |
|-----------------|----------------|------|
| `page.goto(url)` | `browser.open <url>` | 打开新页面或 `browser.nav <url>` 当前导航 |
| `page.goBack()` | `browser.back` | 完全对应 |
| `page.goForward()` | `browser.forward` | 完全对应 |
| `page.reload()` | `browser.eval "location.reload()"` | 无专用命令，用 eval 实现 |
| `page.close()` | `browser.tab.close` | 关闭当前标签页 |
| `page.title()` | `browser.title` | 完全对应 |
| `page.url()` | `browser.url` | 完全对应 |
| `page.viewportSize()` | 无（只读） | 用 `browser.viewport` 设置后隐式获取 |
| `page.setViewportSize({w,h})` | `browser.viewport <wxh>` | 语法不同：`browser.viewport 1920x1080` |
| `page.screenshot()` | `browser.screenshot` | 完全对应 |
| `page.screenshot({element:el})` | `browser.screenshot.element <sel>` | Playwright 用 `element.screenshot()` |
| `page.content()` | `browser.content` | 完全对应 |
| `page.click(selector)` | `browser.click <selector>` | 完全对应 |
| `page.fill(selector, value)` | `browser.type <selector> <text>` | Playwright 的 fill 自动清空；type 模拟逐键 |
| `page.type(selector, text)` | `browser.type <selector> <text>` | 注意：browser.type 内部等价于 fill |
| `page.selectOption(selector, value)` | `browser.select <selector> <value>` | 完全对应 |
| `page.check(selector)` | `browser.check <selector>` | 完全对应 |
| `page.uncheck(selector)` | `browser.uncheck <selector>` | 完全对应 |
| `page.waitForSelector(sel, opts)` | `browser.wait.selector <sel>` | browser.* 无 `state: hidden` 参数 |
| `page.waitForLoadState('load')` | `browser.wait.nav` | 完全对应 |
| `page.waitForTimeout(ms)` | `browser.wait <ms>` | 完全对应 |
| `page.evaluate(js)` | `browser.eval <js>` | 完全对应 |
| `page.$eval(sel, js)` | `browser.eval "document.querySelector(sel)..."` | 需手动组合选择器 + JS |
| `page.$$eval(sel, js)` | `browser.eval "document.querySelectorAll(sel)..."` | 同上，browser.* 无专用批量 eval |
| `page.addInitScript(script)` | `browser.inject <script>` | 注入时机不同：Playwright 在页面创建时注入 |
| `page.addStyleTag({content:css})` | `browser.inject <css>` | MengPaw 在运行时注入 |
| `page.cookies()` | `browser.cookies` | 完全对应 |
| `context.addCookies(cookies)` | `browser.cookies.set <k=v>` | 语法不同，browser.* 更简洁 |
| `context.clearCookies()` | `browser.cookies.clear` | 完全对应 |
| `page.keyboard.press(key)` | `browser.eval "事件模拟"` | 无专用命令 |
| `page.mouse.wheel(dx, dy)` | `browser.scroll <px>` | 简化版：只支持垂直滚动 |
| `page.isVisible(selector)` | `browser.visible <selector>` | 完全对应 |
| `page.isEnabled(selector)` | `browser.enabled <selector>` | 完全对应 |
| `browser.newContext()` | `browser.tab.open <url>` | 新版标签页，非新上下文 |

## 不支持 / 需变通

| Playwright 功能 | 变通方案 |
|-----------------|----------|
| `page.route()` (网络拦截) | 无直接等价；用 `browser.eval` 覆盖 `fetch` / `XMLHttpRequest` |
| `page.on('console')` | 无直接等价；用 `browser.eval` 重写 `console.log` 捕获 |
| `page.on('dialog')` | 无直接等价；用 `browser.eval` 重写 `alert/confirm/prompt` |
| `page.emulateMedia()` | 无直接等价 |
| `page.pdf()` | 无；用 `browser.screenshot` 替代 |
| `locator` API (链式定位) | 用 CSS 选择器组合替代 |
| `page.pause()` (调试暂停) | 无；用 `browser.wait <ms>` 替代 |

## 关键差异：Playwright vs MengPaw WebView

| 方面 | Playwright | MengPaw (Android WebView) |
|------|-----------|--------------------------|
| 渲染引擎 | Chromium (Headless) | Android System WebView (Chromium 变体) |
| 浏览器环境 | 完整的桌面 Chrome | 移动端 Chrome 精简版 |
| 视口默认值 | 1280x720 | 设备屏幕尺寸（通常 360x640~412x896） |
| User-Agent | 桌面 Chrome UA | Android WebView UA（含 `wv` 标记） |
| JavaScript 支持 | 完整 | 完整（默认开启） |
| Cookie 隔离 | 每个 context 隔离 | 系统单实例，共享 CookieStore |
| WebGL/Canvas | 完整 | 有限（取决于设备 GPU） |
| CSS 特性 | 最新 Chromium | 取决于系统 WebView 版本（通常滞后 1-2 个版本） |
| 网络拦截 | `page.route()` 原生支持 | 需通过 eval 覆盖全局函数 |
| 多标签页 | 每个 page 独立 context | 同一 WebView 容器内的标签页 |

## 迁移示例

**Playwright:**
```js
const page = await browser.newPage();
await page.goto('https://example.com');
await page.fill('#search', 'query');
await page.click('#submit');
const title = await page.title();
await page.screenshot({path: 'shot.png'});
```

**MengPaw:**
```
browser.open https://example.com
browser.type "#search" "query"
browser.click "#submit"
let $title = browser.title
browser.screenshot "shot.png"
```
