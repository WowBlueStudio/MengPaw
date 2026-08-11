// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mengpaw.browser.util.BrowserStorage
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
                val dir = File(BrowserStorage.screenshotsDir())
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

    /**
     * 精确等待页面加载（page.goto 语义，替代 browser.nav 的固定 1.5s）。
     *
     * - `domcontentloaded`: progress==100 且 readyState interactive/complete；
     * - `networkidle`: 加载完成后额外 300ms 无新网络活动（近似实现，WebView 无标准 API）。
     * 超时返回错误 JSON（不抛异常，命令层如实转述）。
     */
    fun waitForLoad(mode: String, timeoutMs: Int = 30000): String {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1000, 60000)
        val networkIdle = mode == "networkidle"
        var progress = webView.progress
        var readyState = ""
        while (System.currentTimeMillis() < deadline) {
            progress = webView.progress
            val r = evalJs("(function(){return document.readyState;})()")
            readyState = r.trim().trim('"')
            val loaded = progress >= 100 && (readyState == "complete" || readyState == "interactive")
            if (loaded) {
                if (networkIdle) {
                    // 近似 networkidle: 加载完成后再观察 300ms 网络稳定
                    try { Thread.sleep(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    if (webView.progress >= 100) {
                        return """{"ok":true,"mode":"networkidle","progress":100,"readyState":"$readyState"}"""
                    }
                } else {
                    return """{"ok":true,"mode":"domcontentloaded","progress":100,"readyState":"$readyState"}"""
                }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return """{"ok":false,"error":"load timeout after ${timeoutMs}ms","progress":$progress,"readyState":"$readyState"}"""
    }

    /** 导航 + 精确等待（page.goto）。loadUrl 必须主线程。 */
    fun goto(url: String, waitMode: String = "domcontentloaded", timeoutMs: Int = 30000): String {
        try {
            webView.post { webView.loadUrl(url) }
        } catch (e: Exception) {
            return """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
        return waitForLoad(waitMode, timeoutMs)
    }

    /** 派发按键事件（page.key）。特殊键走映射表，单字符按 ASCII。 */
    @JavascriptInterface
    fun key(keyName: String): String {
        return evalJs(keyScript(escapeJs(keyName)))
    }

    /** 原始页面文本（保留换行）— page.content 的 --grep/--head/--tail 过滤基础。 */
    @JavascriptInterface
    fun contentRaw(maxChars: Int = 50000): String {
        return evalJs(
            "(function(){var t=document.body?document.body.innerText:'';" +
                "return JSON.stringify({ok:true,title:document.title||'',url:location.href,text:t.substring(0,$maxChars)});})()"
        )
    }

    /** 分段全页截图（决策 #5）— 超长页按段返回，坐标按段拆分。 */
    @JavascriptInterface
    fun screenshotFullSegments(maxHeight: Int = 15000): String {
        return screenshotter.captureSegments(maxHeight)
    }

    /** 按段坐标点击（决策 #5）— 段号 + 段内截图坐标。 */
    @JavascriptInterface
    fun coordClickSegment(seg: Int, x: Int, y: Int): String {
        return screenshotter.tapSegment(seg, x, y)
    }

    /** 按段坐标滚动（决策 #5）。 */
    @JavascriptInterface
    fun coordScrollSegment(seg: Int, y: Int): String {
        return screenshotter.scrollToSegmentY(seg, y)
    }

    /** 元素截图 — 视口内滚动定位元素后裁剪（page.screenshot.element）。 */
    @JavascriptInterface
    fun screenshotElement(selector: String): String {
        val safe = escapeJs(selector)
        return try {
            // 元素可能存在视口外 — 先滚动到元素位置再取 rect (getBoundingClientRect 为视口坐标)
            val wv = webView
            val scrollLatch = java.util.concurrent.CountDownLatch(1)
            wv.post {
                wv.evaluateJavascript(
                    "(function(){var e=document.querySelector('$safe');if(!e)return 'not found';e.scrollIntoView({block:'center'});return 'ok';})()"
                ) { scrollLatch.countDown() }
            }
            scrollLatch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            val rectJson = eval(
                "(function(){var e=document.querySelector('$safe');if(!e)return JSON.stringify({ok:false,error:'not found'});var r=e.getBoundingClientRect();return JSON.stringify({ok:true,x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)});})()"
            )
            val json = org.json.JSONObject(rectJson)
            if (!json.optBoolean("ok", false)) {
                return """{"ok":false,"error":"not found: $safe"}"""
            }
            val vpW = wv.width.coerceAtLeast(1)
            val vpH = wv.height.coerceAtLeast(1)
            val scale = if (vpW.toLong() * vpH > MAX_SCREENSHOT_PIXELS)
                kotlin.math.sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / (vpW.toLong() * vpH)).toFloat()
            else 1f
            val bmpW = (vpW * scale).toInt().coerceAtLeast(1)
            val bmpH = (vpH * scale).toInt().coerceAtLeast(1)
            val fullBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val drawLatch = java.util.concurrent.CountDownLatch(1)
            wv.post {
                val c = android.graphics.Canvas(fullBitmap)
                if (scale < 1f) c.scale(scale, scale)
                wv.draw(c)
                drawLatch.countDown()
            }
            drawLatch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            val x = (json.optInt("x", 0) * scale).toInt().coerceIn(0, bmpW - 1)
            val y = (json.optInt("y", 0) * scale).toInt().coerceIn(0, bmpH - 1)
            val w = minOf((json.optInt("w", fullBitmap.width) * scale).toInt().coerceAtLeast(1), fullBitmap.width - x)
            val h = minOf((json.optInt("h", fullBitmap.height) * scale).toInt().coerceAtLeast(1), fullBitmap.height - y)
            val cropped = Bitmap.createBitmap(fullBitmap, x, y, w, h)
            fullBitmap.recycle()
            val file = File(com.mengpaw.browser.util.BrowserStorage.screenshotsDir(), "element_${System.currentTimeMillis()}.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
            cropped.recycle()
            """{"ok":true,"path":"${file.absolutePath}","rect":{"x":$x,"y":$y,"w":$w,"h":$h}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

}
