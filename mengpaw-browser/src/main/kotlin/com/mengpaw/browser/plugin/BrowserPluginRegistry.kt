// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.plugin

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mengpaw.core.plugin.PluginManager
import com.mengpaw.core.plugin.PluginStatus

/**
 * Singleton registry for browser plugins.
 *
 * Bridges MengPaw's core Plugin framework to the browser's WebView hooks.
 * Community plugins implement [BrowserPlugin] and register here.
 *
 * ## Agent CLI Integration
 *
 * Agent can query available browser capabilities via:
 *   memory.read browser-tools
 *
 * Or directly via CLI:
 *   plugin.info <browser-plugin-id>
 *
 * ## Hooks called by BrowserActivity:
 *   onPageStarted → onPageFinished → shouldIntercept → injectScript → injectStyle
 *   menuItems → onLongPress
 */
object BrowserPluginRegistry {
    private val plugins = mutableListOf<BrowserPlugin>()

    /** Register a browser plugin. */
    fun register(plugin: BrowserPlugin) {
        if (plugin !in plugins) plugins.add(plugin)
    }

    /** Unregister a browser plugin. */
    fun unregister(plugin: BrowserPlugin) {
        plugins.remove(plugin)
    }

    /** Get all registered plugins. */
    fun all(): List<BrowserPlugin> = plugins.toList()

    // ── Hooks called by BrowserActivity ──────────────────────────

    fun onPageStarted(url: String) = plugins.forEach { it.onPageStarted(url) }

    fun onPageFinished(url: String, title: String): String? {
        val scripts = plugins.mapNotNull { it.onPageFinished(url, title) }
        return scripts.joinToString(";\n").ifBlank { null }
    }

    fun shouldIntercept(request: WebResourceRequest): WebResourceResponse? {
        plugins.forEach { it.shouldIntercept(request)?.let { return it } }
        return null
    }

    fun injectScripts(url: String): String? {
        val scripts = plugins.mapNotNull { it.injectScript(url) }
        return scripts.joinToString(";\n").ifBlank { null }
    }

    fun injectStyles(url: String): String? {
        val styles = plugins.mapNotNull { it.injectStyle(url) }
        return styles.joinToString("\n").ifBlank { null }
    }

    fun menuItems(): List<BrowserMenuItem> = plugins.flatMap { it.menuItems() }

    /**
     * Returns menu items ONLY from plugins that are ACTIVE in the PluginManager.
     * This ensures uninstalled/disabled plugins don't show dead buttons.
     * Falls back to all menu items if no PluginManager is bound.
     */
    fun activeMenuItems(): List<BrowserMenuItem> {
        val pm = pluginManager
        return if (pm != null) {
            plugins.filter { plugin ->
                val status = pm.status(plugin.metadata.id)
                status == PluginStatus.ACTIVE
            }.flatMap { it.menuItems() }
        } else plugins.flatMap { it.menuItems() }
    }

    /** Optional binding to PluginManager for plugin-status-aware filtering. */
    var pluginManager: PluginManager? = null

    fun onLongPress(element: BrowserElement): List<BrowserAction> =
        plugins.flatMap { it.onLongPress(element) }

    // ── CLI capabilities for Agent ───────────────────────────────

    /**
     * Generate a CLI-readable capabilities document.
     * Agent can read this via `memory.read browser-tools` to understand
     * what browser hooks are available for plugin development.
     */
    fun getCapabilities(): String = """
# MP浏览器 插件开发能力 (Browser Plugin API)

## 可用钩子 (Hooks)

### 页面生命周期
| 钩子 | 触发时机 | 参数 | 返回值 |
|------|---------|------|--------|
| onPageStarted | 页面开始加载 | url: String | void |
| onPageFinished | 页面加载完成 | url, title | JS脚本(可选) |

### 内容拦截与注入
| 钩子 | 触发时机 | 参数 | 返回值 |
|------|---------|------|--------|
| shouldIntercept | 每个资源请求 | WebResourceRequest | WebResourceResponse?(null=允许) |
| injectScript | 每页加载后 | url: String | JS脚本字符串(可选) |
| injectStyle | 每页加载后 | url: String | CSS样式字符串(可选) |

### UI 扩展
| 钩子 | 触发时机 | 参数 | 返回值 |
|------|---------|------|--------|
| menuItems | 菜单打开时 | 无 | List<BrowserMenuItem> |
| onLongPress | 长按元素时 | BrowserElement | List<BrowserAction> |

## 数据结构

### BrowserElement
- type: "IMAGE" | "VIDEO" | "LINK" | "QR"
- url: String
- alt: String
- width, height: Int

### BrowserMenuItem
- label: String  (菜单显示名)
- command: String (点击执行的CLI命令)

### BrowserAction
- label: String  (动作显示名)
- command: String (点击执行的CLI命令)

## Agent 如何开发浏览器插件

1. 参考现有插件: fs-plugin / net-plugin / vision-plugin
2. 实现 BrowserPlugin 接口 (继承 Plugin + 浏览器钩子)
3. 注册到 BrowserPluginRegistry.register(plugin)
4. 打包为 .jar，通过 plugin.install 安装

## 已安装浏览器插件
${plugins.joinToString("\n") { "- ${it.metadata.id} v${it.metadata.version}: ${it.metadata.description}" }.ifBlank { "(暂无)" }}
""".trimIndent()
}
