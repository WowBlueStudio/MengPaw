// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mengpaw.kernel.DataPaths
import java.io.File
import java.io.FileOutputStream

/**
 * P2 fix: 截图位图内存上限 — ARGB_8888 = 4B/px, 32MB ≈ 8.4M px。
 * 超过上限的截图 (超大页面缝合图 / 超宽视口) 等比缩小绘制 (canvas.scale),
 * 坐标空间随返回 JSON 尺寸同步缩放, 防止 Bitmap OOM。
 */
const val MAX_SCREENSHOT_PIXELS = 8 * 1024 * 1024

/**
 * Java↔JavaScript bridge enabling Agent to control the browser.
 *
 * Registered via [WebView.addJavascriptInterface] as "MengPaw".
 * All methods return JSON strings for consistent parsing by Agent.
 *
 * v0.32.x (400 行文件拆分批次 2): JS 脚本常量拆至 [BrowserScripts.kt],
 * 全页缝合截图/坐标交互拆至 [FullPageScreenshotter.kt]。
 * 注意: @JavascriptInterface 方法必须保留在本类实例上 (addJavascriptInterface
 * 注册对象), 拆分仅限实现委托与脚本常量。
 */
class BrowserBridge(
    private val webView: WebView,
    private val onScreenshot: ((Bitmap) -> String)? = null
) {

    private val screenshotter = FullPageScreenshotter(
        webView = webView,
        onScreenshot = onScreenshot,
        unquoteJs = ::unquoteJs,
        viewportFallback = ::screenshot
    )

    /**
     * Click the first element matching a CSS selector.
     * Returns JSON: {"ok":true} or {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun click(selector: String): String {
        return evalJs(clickScript(escapeJs(selector)))
    }

    /**
     * Type text into the first element matching a CSS selector.
     * Returns JSON: {"ok":true} or {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun type(selector: String, text: String): String {
        return evalJs(typeScript(escapeJs(selector), escapeJs(text)))
    }

    /**
     * Scroll the page by (x, y) pixels.
     */
    @JavascriptInterface
    fun scroll(x: Float, y: Float): String {
        return evalJs(scrollScript(x, y))
    }

    /**
     * Extract structured page content as JSON.
     * Returns title, links, forms, headings, and visible text.
     * Text is capped at 3000 chars to keep Agent context lean.
     */
    @JavascriptInterface
    fun content(): String {
        return evalJs(contentScript())
    }

    /**
     * Wait for an element matching the CSS selector to appear in the DOM.
     * Polls every 100ms up to the specified timeout (default 5000ms).
     * Returns JSON: {"ok":true,"found":true} or {"ok":false,"error":"timeout"}
     *
     * 修复: 原实现 setTimeout 异步检查后立即返回 '__PENDING__' — evaluateJavascript
     * 回调拿到的永远是中间态，真实结果永久丢失。改用 Kotlin 侧轮询: 每次调用都是
     * 同步检查（JS 立即返回），间隔 100ms 重试；轮询间隙页面事件循环正常运转，
     * 异步渲染的元素（SPA/网络回包后出现的节点）能被观察到。JS 内忙等则会饿死
     * 页面自身 JS，异步出现的元素永远不会出现，故不采用。
     */
    @JavascriptInterface
    fun waitForSelector(selector: String, timeoutMs: Int = 5000): String {
        val safe = escapeJs(selector)
        val total = timeoutMs.coerceIn(0, 30000)
        val deadline = System.currentTimeMillis() + total
        while (true) {
            val r = evalJs(waitForSelectorCheckScript(safe))
            val obj = try { org.json.JSONObject(r) } catch (_: Exception) { null }
            if (obj?.optBoolean("found", false) == true) return r
            val err = obj?.optString("error")
            // 非"未找到"的错误（选择器非法/求值超时/webview 分离等）→ 如实返回，不再重试
            if (err != null && err != "not found yet") return r
            if (System.currentTimeMillis() >= deadline) {
                return """{"ok":false,"error":"timeout: selector not found after ${total}ms: $safe"}"""
            }
            try { Thread.sleep(100) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return """{"ok":false,"error":"interrupted"}"""
            }
        }
    }

    /**
     * Get all cookies for the current URL, or set/clear cookies.
     */
    @JavascriptInterface
    fun cookies(): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val url = webView.url ?: ""
            val cookie = cm.getCookie(url)
            """{"ok":true,"cookies":"${cookie ?: ""}"}"""
        } catch (e: Exception) { """{"ok":false,"error":"${e.message}"}""" }
    }

    /** Set a cookie. Usage: bridge.cookieSet("name", "value", "example.com") */
    fun cookieSet(name: String, value: String, domain: String? = null): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val url = domain ?: (webView.url ?: "")
            cm.setCookie(url, "$name=$value; Path=/")
            cm.flush()
            """{"ok":true}"""
        } catch (e: Exception) { """{"ok":false,"error":"${e.message}"}""" }
    }

    /** Clear all cookies. */
    fun cookieClear(): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            """{"ok":true}"""
        } catch (e: Exception) { """{"ok":false,"error":"${e.message}"}""" }
    }

    /** Get/set/clear localStorage or sessionStorage. */
    @JavascriptInterface
    fun storage(type: String, op: String, key: String? = null, value: String? = null): String {
        val storageType = if (type == "session") "sessionStorage" else "localStorage"
        return when (op) {
            "get" -> evalJs(storageGetScript(storageType, escapeJs(key ?: "")))
            "set" -> evalJs(storageSetScript(storageType, escapeJs(key ?: ""), escapeJs(value ?: "")))
            "clear" -> evalJs(storageClearScript(storageType))
            else -> """{"ok":false,"error":"Unknown op: $op (use get/set/clear)"}"""
        }
    }

    /** Get element attribute value. */
    @JavascriptInterface
    fun attr(selector: String, attribute: String): String {
        return evalJs(attrScript(escapeJs(selector), escapeJs(attribute)))
    }

    /** Get element text content. */
    @JavascriptInterface
    fun text(selector: String): String {
        return evalJs(textScript(escapeJs(selector)))
    }

    /** Check if element is visible (has non-zero dimensions and is not hidden). */
    @JavascriptInterface
    fun visible(selector: String): String {
        return evalJs(visibleScript(escapeJs(selector)))
    }

    /** Check if element is enabled (not disabled). */
    @JavascriptInterface
    fun enabled(selector: String): String {
        return evalJs(enabledScript(escapeJs(selector)))
    }

    /** Select an option in a &lt;select&gt; element by value or visible text. */
    @JavascriptInterface
    fun select(selector: String, value: String): String {
        return evalJs(selectScript(escapeJs(selector), escapeJs(value)))
    }

    /** Submit a form. */
    @JavascriptInterface
    fun submit(selector: String): String {
        return evalJs(submitScript(escapeJs(selector)))
    }

    /** Check a checkbox or radio input. */
    @JavascriptInterface
    fun check(selector: String): String {
        return evalJs(checkScript(escapeJs(selector)))
    }

    /** Uncheck a checkbox. */
    @JavascriptInterface
    fun uncheck(selector: String): String {
        return evalJs(uncheckScript(escapeJs(selector)))
    }

    /**
     * Execute arbitrary JavaScript in the page and return the result.
     * Result is truncated to 5000 chars for safety.
     * SECURITY: NOT exposed via @JavascriptInterface — only callable from Kotlin (Agent).
     */
    fun eval(js: String): String {
        return evalJs("""
            (function() {
                try {
                    var result = eval(${js.toJsonLiteral()});
                    if (result === undefined) return 'undefined';
                    if (result === null) return 'null';
                    var s = typeof result === 'string' ? result : JSON.stringify(result);
                    return s.length > 5000 ? s.substring(0,5000)+'...[truncated]' : s;
                } catch(e) { return 'Error: '+e.message; }
            })()
        """.trimIndent())
    }

    // ── Internal ────────────────────────────────────────────────────────

    /**
     * Execute JS and return result. Uses a short timeout to avoid
     * blocking WebView's JavaBridge thread pool indefinitely.
     *
     * SAFETY: Called from @JavascriptInterface (JavaBridge thread).
     * A long block here can exhaust the WebView thread pool → crash.
     * Timeout is set to 2s max; on timeout, returns error JSON.
     */
    private fun evalJs(script: String): String {
        // __mp calls are pure JS sync (<10ms, no DOM traversal) — fast path handled by caller scripts
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = """{"ok":false,"error":"timeout"}"""
        try {
            val posted = webView.post {
                try {
                    webView.evaluateJavascript(script) { r ->
                        result = unquoteJs(r)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    result = """{"ok":false,"error":"${escapeJs(e.message ?: "unknown")}"}"""
                    latch.countDown()
                }
            }
            if (!posted) {
                // WebView handler is gone (destroyed or shutting down)
                return """{"ok":false,"error":"webview detached"}"""
            }
            val ok = latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                // Timeout — main thread is likely busy. Don't block longer.
                // The evaluateJavascript callback will still fire, but we
                // can't wait for it without risking thread pool exhaustion.
                return """{"ok":false,"error":"evaluation timeout (main thread busy)"}"""
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            result = """{"ok":false,"error":"interrupted"}"""
        } catch (e: Exception) {
            result = """{"ok":false,"error":"${escapeJs(e.message ?: "unknown")}"}"""
        }
        return result
    }

    /** Remove the JSON-string quoting that evaluateJavascript adds. */
    private fun unquoteJs(raw: String): String {
        var s = raw.trim()
        if (s == "null") return """{"ok":false,"error":"JS returned null"}"""
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
            s = s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return s
    }

    /** Escape a string for safe embedding in a JS string literal. */
    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\")
         .replace("'", "\\'")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "")

    /** Convert a Kotlin string to a JS JSON string literal. */
    private fun String.toJsonLiteral(): String =
        "'" + this.replace("\\", "\\\\")
               .replace("'", "\\'")
               .replace("\n", "\\n") + "'"

    // ── Persistent bridge (speed optimization) ──────────────────────────

    /**
     * Inject the persistent `__mp` helper object into the page.
     * After injection, all subsequent commands use tiny one-liners:
     *   __mp.c('#btn') instead of full click script (~500→15 chars, ~33x smaller)
     *
     * Call once per page load. Subsequent calls are no-ops.
     */
    @JavascriptInterface
    fun inject(): String {
        return evalJs(injectScript())
    }

    /**
     * Fast-path click using pre-injected __mp bridge.
     * Falls back to full script if __mp not available.
     */
    fun fastClick(selector: String): String {
        // 修复: 原实现只转义单引号 — '\' '"' 换行等均可注入。改用 escapeJs 完整转义
        // （转义后的字符串在单引号 JS 字面量中同样安全，双引号转义无害）。
        val s = escapeJs(selector)
        return evalJs(fastClickScript(s))
    }

    /** Fast-path type. */
    fun fastType(selector: String, text: String): String {
        val s = escapeJs(selector); val t = escapeJs(text)
        return evalJs(fastTypeScript(s, t))
    }

    /** Fast-path content. Returns diff if cached, full content otherwise. */
    fun fastContent(): String {
        return evalJs(fastContentScript())
    }

    /** Fast-path diff — returns only changed text since last extraction. */
    @JavascriptInterface
    fun diff(): String {
        return evalJs(diffScript())
    }

    /**
     * Capture a screenshot of the current visible viewport.
     * Uses [View.draw] on a Canvas-backed Bitmap (API 26+ compatible).
     * Saves to DataPaths.SCREENSHOTS and returns the file path.
     */
    @JavascriptInterface
    fun screenshot(): String {
        return try {
            // Use View.draw() instead of deprecated capturePicture() (removed in API 33+)
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            webView.draw(canvas)
            val path = onScreenshot?.invoke(bitmap) ?: run {
                val dir = File(DataPaths.SCREENSHOTS)
                dir.mkdirs()
                val file = File(dir, "browser_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                file.absolutePath
            }
            bitmap.recycle()
            """{"ok":true,"path":"$path","width":${webView.width},"height":${webView.height}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    // ── Quick Click (委托 FullPageScreenshotter) ────────────────────────

    /**
     * Quick Click: capture a stitched full-page screenshot.
     * 实现见 [FullPageScreenshotter.capture] — 视口逐段滚动截图拼接。
     * Returns JSON with the file path, total width, total height, and segment count.
     */
    @JavascriptInterface
    fun screenshotFull(maxHeight: Int = 15000): String {
        return screenshotter.capture(maxHeight)
    }

    /**
     * Quick Click: tap at absolute coordinates relative to the FULL page.
     * 实现见 [FullPageScreenshotter.tap] — 按最近截图缩放比还原页面坐标后派发触摸事件。
     */
    @JavascriptInterface
    fun coordClick(x: Int, y: Int): String {
        return screenshotter.tap(x, y)
    }

    /**
     * Quick Click: scroll to a specific y-coordinate in the full page.
     * 实现见 [FullPageScreenshotter.scrollToY]。
     */
    @JavascriptInterface
    fun coordScroll(y: Int): String {
        return screenshotter.scrollToY(y)
    }
}
