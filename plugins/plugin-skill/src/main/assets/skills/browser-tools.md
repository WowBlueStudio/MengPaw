---
name: browser-tools
description: 浏览器插件开发 API 参考 — Plugin 接口、数据类型、开发指南；替代 plugin-index.md 中的失效链接。触发词：「浏览器插件开发」「浏览器插件 API」
enabled: true
category: browser
---
# 浏览器插件 API 参考

本文档描述 MengPaw 浏览器插件系统的接口与数据类型，供开发者编写自定义浏览器插件使用。

> 此文档替代 `plugin-index.md` 中失效的 `memory read browser-tools` 链接指向的内容（现可通过 `agent.cli` 的 BROWSER TOOLS 段查看）。

## 一、BrowserPlugin 接口

所有浏览器插件需实现 `BrowserPlugin` 接口：

| 方法 | 签名 | 说明 |
|------|------|------|
| `onPageStarted` | `(url: String) -> Unit` | 页面开始加载时触发，参数为正在加载的 URL |
| `onPageFinished` | `(url: String) -> Unit` | 页面加载完成时触发 |
| `shouldIntercept` | `(request: InterceptRequest) -> InterceptResult?` | 请求拦截回调；返回 `null` 表示不拦截，返回 InterceptResult 替换响应 |
| `injectScript` | `() -> String?` | 页面加载完成后注入的 JavaScript 代码；返回 null 则不注入 |
| `injectStyle` | `() -> String?` | 页面加载完成后注入的 CSS 代码；返回 null 则不注入 |
| `menuItems` | `() -> List<BrowserMenuItem>` | 长按菜单中注册的上下文菜单项 |
| `onLongPress` | `(element: BrowserElement) -> Unit` | 用户长按页面元素时触发（需先注册 menuItems） |

### 默认实现
```kotlin
open class BaseBrowserPlugin : BrowserPlugin {
    override fun onPageStarted(url: String) {}
    override fun onPageFinished(url: String) {}
    override fun shouldIntercept(request: InterceptRequest): InterceptResult? = null
    override fun injectScript(): String? = null
    override fun injectStyle(): String? = null
    override fun menuItems(): List<BrowserMenuItem> = emptyList()
    override fun onLongPress(element: BrowserElement) {}
}
```

## 二、数据类型

### BrowserElement

表示页面中的一个 DOM 元素：

| 字段 | 类型 | 说明 |
|------|------|------|
| `tagName` | String | HTML 标签名（大写，如 `DIV`、`A`） |
| `id` | String? | 元素的 `id` 属性 |
| `classes` | List<String> | 元素的 class 列表 |
| `text` | String? | 元素的纯文本内容 |
| `href` | String? | 如为链接，目标 URL |
| `src` | String? | 如图片/脚本，资源 URL |
| `attributes` | Map<String, String> | 元素的所有属性键值对 |
| `bounds` | Rect? | 元素在页面中的位置和尺寸 |
| `xpath` | String | 元素的 XPath 路径 |

### BrowserMenuItem

上下文菜单项定义：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 菜单项唯一标识 |
| `label` | String | 菜单显示文本 |
| `icon` | Drawable? | 可选图标 |

### InterceptRequest

请求拦截时的请求对象：

| 字段 | 类型 | 说明 |
|------|------|------|
| `url` | String | 请求 URL |
| `method` | String | HTTP 方法（GET、POST 等） |
| `headers` | Map<String, String> | 请求头 |
| `body` | ByteArray? | 请求体（POST 等） |

### InterceptResult

拦截替换响应：

| 字段 | 类型 | 说明 |
|------|------|------|
| `data` | ByteArray | 替换的响应体 |
| `mimeType` | String | MIME 类型（如 `text/html`） |
| `encoding` | String | 编码（如 `UTF-8`） |
| `headers` | Map<String, String>? | 自定义响应头 |

### BrowserAction

BrowserPlugin 可触发的操作枚举：

| 值 | 说明 |
|----|------|
| `NAVIGATE` | 导航到新 URL |
| `RELOAD` | 刷新当前页面 |
| `BACK` | 返回上一页 |
| `FORWARD` | 前进到下一页 |
| `EXECUTE_JS` | 执行 JavaScript |
| `INJECT_CSS` | 注入 CSS |
| `SHOW_TOAST` | 显示短提示 |
| `DOWNLOAD` | 下载资源 |

## 三、插件开发示例

### 1. 广告拦截插件
```kotlin
class AdBlockPlugin : BaseBrowserPlugin() {
    override fun shouldIntercept(request: InterceptRequest): InterceptResult? {
        val blocklist = listOf("doubleclick.net", "googleadservices.com", "adservice")
        if (blocklist.any { request.url.contains(it) }) {
            return InterceptResult(
                data = "".toByteArray(),
                mimeType = "text/html",
                encoding = "UTF-8"
            )
        }
        return null
    }
}
```

### 2. 自定义样式插件
```kotlin
class DarkModePlugin : BaseBrowserPlugin() {
    override fun injectStyle(): String = """
        body { background: #1a1a2e !important; color: #e0e0e0 !important; }
        a { color: #64b5f6 !important; }
        img { filter: brightness(0.8) !important; }
    """.trimIndent()
}
```

### 3. 长按快捷操作插件
```kotlin
class QuickSearchPlugin : BaseBrowserPlugin() {
    override fun menuItems(): List<BrowserMenuItem> = listOf(
        BrowserMenuItem("search_baidu", "百度搜索"),
        BrowserMenuItem("translate", "翻译此文本")
    )

    override fun onLongPress(element: BrowserElement) {
        if (element.text != null) {
            // 根据选中的菜单项执行相应操作
        }
    }
}
```

## 四、插件注册

在 `plugin.json` 中注册浏览器插件：
```json
{
  "name": "my-browser-plugin",
  "type": "browser",
  "main": "com.example.MyBrowserPlugin",
  "description": "我的浏览器插件",
  "version": "1.0.0"
}
```

## 五、注意事项

1. **线程安全**：BrowserPlugin 回调在 WebView 的主线程上调用，避免执行耗时操作；如需网络请求或 IO，请使用协程切到 IO 线程。
2. **性能影响**：`shouldIntercept` 拦截所有网络请求，请确保过滤逻辑高效，避免拖慢页面加载。
3. **内存泄漏**：在插件销毁时请清理对 Context 和 WebView 的引用。
4. **JavaScript 注入时机**：`injectScript` 在 `onPageFinished` 之前执行，确保 JS 在页面渲染前注入。
5. **CSP 限制**：目标页面如果设置了严格的 Content-Security-Policy，可能会阻止 injectScript 注入的内联脚本。此时可考虑使用 injectStyle 注入 CSS，或通过消息通道间接执行 JS。
