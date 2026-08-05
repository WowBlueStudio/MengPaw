// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import android.webkit.WebView
import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector

/**
 * Tab metadata exposed to Agent for multi-tab control.
 */
data class BrowserTab(
    val id: Int,
    val url: String,
    val title: String,
    val isLoading: Boolean,
    val isActive: Boolean
)

/**
 * Built-in browser plugin providing browser.* CLI commands for Agent.
 *
 * ## Multi-tab control (4 tabs max)
 *   browser.tabs                — list all tabs
 *   browser.tab <N>             — switch to tab N
 *   browser.tab.open <N> <url>  — open URL in tab N (auto-creates if needed)
 *   browser.tab.close <N>       — close tab N
 *   browser.tab.all             — extract content from ALL tabs in one call
 *
 * ## Efficiency commands
 *   browser.nav <url>           — navigate + wait + auto-extract content
 *   browser.batch <cmd1;;cmd2>  — execute multiple commands in one round-trip
 *   browser.q <shorthand>       — quick selector shortcuts
 *
 * ## Basic control
 *   browser.eval / click / type / scroll / content / screenshot
 *   browser.open / back / forward / title / url
 */
class BuiltinBrowserPlugin(
    private val webViewProvider: () -> WebView?,
    private val tabInfoProvider: () -> List<BrowserTab> = { emptyList() },
    private val tabSwitcher: (Int) -> Unit = {},
    private val tabOpener: (Int, String) -> Unit = { _, _ -> },
    private val tabCloser: (Int) -> Unit = {}
) {
    private val bridge: BrowserBridge? get() {
        val wv = webViewProvider() ?: return null
        return BrowserBridge(wv)
    }

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // Tab management
        "tabs" to ::tabs, "tab" to ::tab, "tab.open" to ::tabOpen,
        "tab.close" to ::tabClose, "tab.all" to ::tabAll,
        // Efficiency
        "nav" to ::nav, "batch" to ::batch, "q" to ::quick,
        "inject" to ::injectBridge, "diff" to ::diff, "preload" to ::preload,
        // Basic
        "eval" to ::eval, "click" to ::click, "type" to ::type,
        "scroll" to ::scroll, "content" to ::content, "screenshot" to ::screenshot,
        "open" to ::open, "back" to ::back, "forward" to ::forward,
        "title" to ::title, "url" to ::url,
        // Page wait
        "wait" to ::waitMs, "wait.selector" to ::waitSelector, "wait.nav" to ::waitNav,
        // Cookies
        "cookies" to ::cookiesGet, "cookies.set" to ::cookiesSet, "cookies.clear" to ::cookiesClear,
        // Dialogs
        "dialog.accept" to ::dialogAccept, "dialog.dismiss" to ::dialogDismiss,
        // Form actions
        "select" to ::selectOpt, "submit" to ::submitForm, "check" to ::checkBox, "uncheck" to ::uncheckBox,
        // Element queries
        "attr" to ::attrGet, "text" to ::textGet, "visible" to ::visibleCheck, "enabled" to ::enabledCheck,
        // Storage
        "storage" to ::storageOp,
        // Element screenshot
        "screenshot.element" to ::screenshotElement, "screenshot.full" to ::screenshotFullCmd,
        // Quick Click (coordinate-based)
        "coord.click" to ::coordClickCmd, "coord.scroll" to ::coordScrollCmd,
        // Viewport & UA
        "viewport" to ::viewportSet, "userAgent" to ::userAgentOp,
        // Version
        "version" to ::versionCmd
    )

    // ═══════════════════════════════════════════════════════════════════
    // Tab management
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun tabs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val all = tabInfoProvider()
        if (all.isEmpty()) return ExecutionResult.ok("(无标签页)")
        return ExecutionResult.ok(buildString {
            appendLine("## 浏览器标签页 (${all.size}/4)")
            appendLine("| ID | 状态 | URL | 标题 |")
            appendLine("|----|------|-----|------|")
            all.forEach { t ->
                val active = if (t.isActive) "▶" else " "
                val load = if (t.isLoading) "…" else "✓"
                appendLine("| $active ${t.id} | $load | ${t.url.take(50)} | ${t.title.take(30)} |")
            }
        })
    }

    private suspend fun tab(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab <N>  — 切换到标签页 N (0-3)\nbrowser.tabs  — 查看所有标签页",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull() ?: return ExecutionResult.fail("标签页ID必须是数字 0-3", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (id !in 0..3) return ExecutionResult.fail("标签页ID范围: 0-3", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        tabSwitcher(id)
        return ExecutionResult.ok("已切换到标签页 $id")
    }

    private suspend fun tabOpen(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab.open <N> <url>  — 在标签页N打开URL\nbrowser.tab.open 0 https://example.com",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull()
        if (id == null || args.size < 2) return ExecutionResult.fail(
            "Usage: browser.tab.open <N> <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args.drop(1).joinToString(" ")
        tabOpener(id, url)
        return ExecutionResult.ok("标签页 $id 正在打开: $url")
    }

    private suspend fun tabClose(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab.close <N>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull() ?: return ExecutionResult.fail("标签页ID必须是数字", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val tabs = tabInfoProvider()
        if (tabs.size <= 1) return ExecutionResult.fail("至少保留一个标签页")
        if (tabs.none { it.id == id }) return ExecutionResult.fail("标签页 $id 不存在", errorCode = ErrorCodes.ERR_NOT_FOUND)
        tabCloser(id)
        return ExecutionResult.ok("已关闭标签页 $id")
    }

    /** Extract content from ALL tabs — Agent's most efficient multi-source reading tool. */
    private suspend fun tabAll(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val all = tabInfoProvider()
        if (all.isEmpty()) return noBrowser()
        // Switch to each tab and extract content
        val results = mutableListOf<String>()
        val wv = webViewProvider() ?: return noBrowser()
        for (t in all) {
            if (!t.isActive) tabSwitcher(t.id)
            kotlinx.coroutines.delay(300) // brief settle
            results.add(BrowserBridge(wv).content().let { json ->
                try {
                    val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
                    """{"tab":${t.id},"url":"${t.url.take(80)}","title":"$title"}"""
                } catch (_: Exception) { """{"tab":${t.id},"url":"${t.url}","error":"parse failed"}""" }
            })
        }
        return ExecutionResult.ok("## 全部标签页内容 (${all.size})\n\n" + results.joinToString("\n---\n"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Efficiency commands
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Navigate to URL and auto-extract content in one step.
     * Saves Agent 2 round-trips (open + content).
     */
    private suspend fun nav(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.nav <url>  — 打开URL并自动提取内容", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        val b = bridge ?: return noBrowser()
        return try {
            b.eval("location.href = '$url'")
            // Brief wait for page start
            kotlinx.coroutines.delay(1500)
            val content = b.content()
            ExecutionResult.ok("## 已导航并提取内容\nURL: $url\n\n$content")
        } catch (e: Exception) {
            ErrorCollector.report(e, "BuiltinBrowser.nav")
            ExecutionResult.fail("Nav error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /**
     * Batch execute multiple commands in one round-trip.
     * Commands separated by ";;" — e.g. browser.batch click #btn ;; type #q hello ;; click #submit
     */
    private suspend fun batch(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.batch <cmd1> ;; <cmd2> ;; ...\n每条子命令格式: click|type|scroll|eval|content <args>",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val input = args.joinToString(" ")
        val cmds = input.split(";;").map { it.trim() }.filter { it.isNotEmpty() }
        if (cmds.isEmpty()) return ExecutionResult.fail("无有效命令", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (cmds.size > 10) return ExecutionResult.fail("单次批处理最多10条命令", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val b = bridge ?: return noBrowser()
        val results = mutableListOf<String>()
        for ((i, cmd) in cmds.withIndex()) {
            val parts = cmd.split(" ", limit = 2)
            val op = parts[0]; val rest = parts.getOrElse(1) { "" }
            val result = try {
                when (op) {
                    "click" -> b.click(rest)
                    "type" -> {
                        val sp = rest.split(" ", limit = 2)
                        b.type(sp.getOrElse(0) { "" }, sp.getOrElse(1) { "" })
                    }
                    "scroll" -> {
                        val sp = rest.split(" ")
                        b.scroll(sp.getOrNull(0)?.toFloatOrNull() ?: 0f, sp.getOrNull(1)?.toFloatOrNull() ?: 500f)
                    }
                    "eval" -> b.eval(rest)
                    "content" -> b.content()
                    else -> """{"ok":false,"error":"unknown batch op: $op"}"""
                }
            } catch (e: Exception) { """{"ok":false,"error":"${e.message}"}""" }
            results.add("[$op] $result")
        }
        return ExecutionResult.ok("批处理完成 (${cmds.size}条):\n" + results.joinToString("\n"))
    }

    /**
     * Quick selector shortcuts for common page elements.
     * browser.q search   → returns common search box selectors
     * browser.q main     → main content area
     * browser.q nav      → navigation elements
     * browser.q forms    → all forms on page
     */
    private suspend fun quick(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.ok("""
## browser.q 快捷选择器

| 缩写 | 说明 | 展开为 |
|------|------|--------|
| q search | 搜索框选择器 | input[type=search],input[name=q],#search,... |
| q main | 主内容区 | main,article,#content,.post,.article |
| q nav | 导航栏 | nav,#nav,.navbar,.menu,.header |
| q forms | 所有表单 | 页面中所有form元素 |
| q links | 所有链接 | 前20个链接 |
| q btn | 所有按钮 | button,input[type=submit],.btn,[role=button] |
| q imgs | 图片列表 | 前10张图片的src/alt |
""".trimIndent())
        val b = bridge ?: return noBrowser()
        return when (args[0]) {
            "search" -> ExecutionResult.ok(b.eval(searchBoxJs()))
            "main" -> ExecutionResult.ok(b.eval(mainContentJs()))
            "nav" -> ExecutionResult.ok(b.eval(navJs()))
            "forms" -> ExecutionResult.ok(b.content()) // content already includes forms
            "links" -> ExecutionResult.ok(b.eval(linksJs()))
            "btn" -> ExecutionResult.ok(b.eval(buttonsJs()))
            "imgs" -> ExecutionResult.ok(b.eval(imagesJs()))
            else -> ExecutionResult.fail("未知快捷: ${args[0]}\n支持: search, main, nav, forms, links, btn, imgs", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Speed-optimized commands
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Inject persistent __mp bridge once per page.
     * After injection, all subsequent commands use ~15-char calls instead of ~500-char scripts.
     * Speed gain: ~33x less data over the Java↔JS bridge per call.
     */
    private suspend fun injectBridge(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val b = bridge ?: return noBrowser()
        return try {
            val r = b.inject()
            ExecutionResult.ok(r + "\n\n后续命令将自动使用快速通道 (__mp.*)。")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.inject"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Get only changed content since last extraction — fraction of the data. */
    private suspend fun diff(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.diff()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.diff"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Preload a URL in a background tab without switching focus. */
    private suspend fun preload(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.preload <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        val tabs = tabInfoProvider()
        // Find first non-active empty tab, or use tab 3
        val target = tabs.firstOrNull { !it.isActive && it.url.isBlank() }?.id
            ?: tabs.firstOrNull { !it.isActive }?.id
            ?: 3
        tabOpener(target, url)
        return ExecutionResult.ok("后台预加载中 (标签页$target): $url\n使用 browser.tab $target 切换查看。")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Basic commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun eval(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.eval <javascript>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.eval(args.joinToString(" "))) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.eval"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun click(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.click <selector>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.click(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.click"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun type(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.type <selector> <text>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.type(args[0], args.drop(1).joinToString(" "))) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.type"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun scroll(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val x = args.getOrNull(0)?.toFloatOrNull() ?: 0f; val y = args.getOrNull(1)?.toFloatOrNull() ?: 500f
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.scroll(x, y)) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.scroll"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun content(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.content()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.content"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun screenshot(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val b = bridge ?: return noBrowser()
        return try {
            val result = b.screenshot()
            ExecutionResult.ok("Screenshot saved: $result")
        } catch (e: Exception) {
            ErrorCollector.report(e, "BuiltinBrowser.screenshot")
            ExecutionResult.fail("Screenshot failed: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
    private suspend fun open(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.open <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { b.eval("location.href = '${args[0]}'"); ExecutionResult.ok("Navigating to: ${args[0]}") }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.open"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun back(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try { if (wv.canGoBack()) { wv.goBack(); ExecutionResult.ok("Back") } else ExecutionResult.ok("Cannot go back") }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.back"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun forward(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try { if (wv.canGoForward()) { wv.goForward(); ExecutionResult.ok("Forward") } else ExecutionResult.ok("Cannot go forward") }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.forward"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun title(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try { ExecutionResult.ok(wv.title ?: "(no title)") }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.title"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun url(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try { ExecutionResult.ok(wv.url ?: "(no url)") }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.url"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page wait commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun waitMs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.wait <milliseconds>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val ms = args[0].toLongOrNull() ?: return ExecutionResult.fail("Invalid milliseconds", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (ms > 30000) return ExecutionResult.fail("Max wait: 30000ms (30s)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        kotlinx.coroutines.delay(ms)
        return ExecutionResult.ok("Waited ${ms}ms")
    }

    private suspend fun waitSelector(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.wait.selector <css> [timeoutMs]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        val timeout = args.getOrNull(1)?.toIntOrNull() ?: 5000
        return try { ExecutionResult.ok(b.waitForSelector(args[0], timeout)) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.waitSelector"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun waitNav(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val timeout = args.firstOrNull()?.toIntOrNull() ?: 10000
        val wv = webViewProvider() ?: return noBrowser()
        val b = bridge ?: return noBrowser()
        return try {
            // Use a simple delay-based approach for navigation wait
            kotlinx.coroutines.delay(timeout.toLong())
            ExecutionResult.ok("Navigation wait completed (${timeout}ms)")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.waitNav"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cookie commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun cookiesGet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.cookies()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.cookies"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun cookiesSet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.cookies.set <name> <value> [domain]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.cookieSet(args[0], args[1], args.getOrNull(2))) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.cookiesSet"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun cookiesClear(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.cookieClear()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.cookiesClear"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Dialog commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun dialogAccept(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try {
            wv.post { wv.evaluateJavascript("(function(){try{if(window.__mpDialogCb){window.__mpDialogCb(true,'');delete window.__mpDialogCb;return'ok';}}catch(e){}})()", null) }
            ExecutionResult.ok("Dialog accepted")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.dialogAccept"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun dialogDismiss(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try {
            wv.post { wv.evaluateJavascript("(function(){try{if(window.__mpDialogCb){window.__mpDialogCb(false);delete window.__mpDialogCb;return'ok';}}catch(e){}})()", null) }
            ExecutionResult.ok("Dialog dismissed")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.dialogDismiss"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Form action commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun selectOpt(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.select <css> <value>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.select(args[0], args[1])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.select"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun submitForm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.submit <form_selector>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.submit(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.submit"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun checkBox(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.check <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.check(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.check"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun uncheckBox(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.uncheck <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.uncheck(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.uncheck"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Element query commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun attrGet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.attr <css> <attribute>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.attr(args[0], args[1])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.attr"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun textGet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.text <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.text(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.text"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun visibleCheck(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.visible <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.visible(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.visible"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun enabledCheck(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.enabled <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.enabled(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.enabled"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Storage commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun storageOp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.storage <local|session> <get|set|clear> [key] [value]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.storage(args[0], args[1], args.getOrNull(2), args.getOrNull(3))) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.storage"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Element screenshot
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun screenshotElement(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.screenshot.element <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val wv = webViewProvider() ?: return noBrowser()
        return try {
            val safe = args[0].replace("\\", "\\\\").replace("'", "\\'")
            // Get element bounds via JS
            val rectJson = com.mengpaw.browser.bridge.BrowserBridge(wv).eval(
                "(function(){var e=document.querySelector('$safe');if(!e)return JSON.stringify({ok:false,error:'not found'});var r=e.getBoundingClientRect();return JSON.stringify({ok:true,x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)});})()"
            )
            val json = org.json.JSONObject(rectJson)
            if (!json.optBoolean("ok", false)) {
                return ExecutionResult.fail("Element not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
            }
            // Full-page screenshot and crop
            val picture = wv.capturePicture()
            val fullBitmap = android.graphics.Bitmap.createBitmap(picture.width, picture.height, android.graphics.Bitmap.Config.ARGB_8888)
            picture.draw(android.graphics.Canvas(fullBitmap))
            val x = json.optInt("x", 0).coerceAtLeast(0)
            val y = json.optInt("y", 0).coerceAtLeast(0)
            val w = minOf(json.optInt("w", fullBitmap.width), fullBitmap.width - x).coerceAtLeast(1)
            val h = minOf(json.optInt("h", fullBitmap.height), fullBitmap.height - y).coerceAtLeast(1)
            val cropped = android.graphics.Bitmap.createBitmap(fullBitmap, x, y, w, h)
            fullBitmap.recycle()
            val file = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS, "element_${System.currentTimeMillis()}.png")
            file.parentFile?.mkdirs()
            java.io.FileOutputStream(file).use { cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            cropped.recycle()
            ExecutionResult.ok("""{"ok":true,"path":"${file.absolutePath}","rect":{"x":$x,"y":$y,"w":$w,"h":$h}}""")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.screenshotElement"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Quick Click — full-page screenshot + coordinate-based interaction
    // ═══════════════════════════════════════════════════════════════════

    /** Stitched full-page screenshot — Agent's primary visual analysis tool. */
    private suspend fun screenshotFullCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!Companion.quickClickEnabled()) return ExecutionResult.fail(
            "Quick Click 已禁用。请在浏览器设置→智能体协同中开启。\nQuick Click is disabled. Enable in Settings → Agent Collaboration.",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )
        val b = bridge ?: return noBrowser()
        val maxH = args.firstOrNull()?.toIntOrNull() ?: Companion.screenshotMaxHeight()
        return try {
            val result = b.screenshotFull(maxH)
            val r = ExecutionResult.ok(result)
            // Append follow-up hint for Agent
            ExecutionResult.ok(result + "\n---\n使用 browser.coord.click <x> <y> 在此截图坐标上点击。参考: skill.run browser-control")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.screenshotFull"); ExecutionResult.fail("${e.message}\n可降级: browser.screenshot (视口截图)", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Tap at absolute page coordinates (from screenshotFull image). */
    private suspend fun coordClickCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!Companion.quickClickEnabled()) return ExecutionResult.fail(
            "Quick Click 已禁用。请在浏览器设置→智能体协同中开启。",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.coord.click <x> <y>\nCoordinates are relative to the full-page screenshot from browser.screenshot.full", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val x = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid X coordinate", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid Y coordinate", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.coordClick(x, y)) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.coordClick"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Scroll to full-page y-coordinate (for verification before clicking). */
    private suspend fun coordScrollCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.coord.scroll <y>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid Y coordinate", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = bridge ?: return noBrowser()
        return try { ExecutionResult.ok(b.coordScroll(y)) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.coordScroll"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Viewport and User-Agent
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun viewportSet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.viewport <width> <height>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val w = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid width", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val h = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid height", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val wv = webViewProvider() ?: return noBrowser()
        return try {
            wv.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');if(m){m.setAttribute('content','width=$w,height=$h,initial-scale=1');}else{m=document.createElement('meta');m.name='viewport';m.content='width=$w,height=$h,initial-scale=1';document.head.appendChild(m);}})()", null)
            ExecutionResult.ok("Viewport set to ${w}x${h}")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.viewport"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun userAgentOp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val wv = webViewProvider() ?: return noBrowser()
        return try {
            if (args.isEmpty()) {
                ExecutionResult.ok("Current UA: ${wv.settings.userAgentString}")
            } else {
                val ua = args.joinToString(" ")
                wv.settings.userAgentString = ua
                ExecutionResult.ok("User-Agent set to: $ua")
            }
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.userAgent"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Version
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun versionCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // 版本号不硬编码 — 随 gradle defaultConfig.versionName (BuildConfig.VERSION_NAME) 自动同步
        return ExecutionResult.ok("MP Browser v${com.mengpaw.browser.BuildConfig.VERSION_NAME} / Android SDK ${android.os.Build.VERSION.SDK_INT}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Quick selector JS snippets
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        /** Providers set by BrowserActivity for toggle-aware command execution. */
        @JvmStatic var quickClickEnabled: () -> Boolean = { true }
        @JvmStatic var screenshotMaxHeight: () -> Int = { 15000 }

        private fun searchBoxJs() = """(function(){var s=document.querySelector('input[type=search],input[name=q],input[name=query],input[name=wd],#search,.search input,[role=search] input,[aria-label*=Search]');if(!s)return JSON.stringify({found:false});return JSON.stringify({found:true,tag:s.tagName,type:s.type||'text',id:s.id||'',name:s.name||'',placeholder:s.placeholder||'',selector:(s.id?'#'+s.id:s.name?'[name='+s.name+']':s.tagName.toLowerCase()+'[type='+(s.type||'text')+']')})})()"""
        private fun mainContentJs() = """(function(){var s=['main','article','#content','.post','.article','.main','#main','[role=main]'];for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(el)return JSON.stringify({found:true,selector:s[i],tag:el.tagName,text:(el.textContent||'').trim().substring(0,200)})}return JSON.stringify({found:false,tip:'Try browser.content for full page'})})()"""
        private fun navJs() = """(function(){var s=['nav','#nav','.navbar','.menu','.header','[role=navigation]'];for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(el)return JSON.stringify({found:true,selector:s[i],links:Array.from(el.querySelectorAll('a[href]')).slice(0,15).map(function(a){return{text:(a.textContent||'').trim().substring(0,40),href:a.href}})})}return JSON.stringify({found:false})})()"""
        private fun linksJs() = """(function(){return JSON.stringify(Array.from(document.querySelectorAll('a[href]')).slice(0,20).map(function(a){return{text:(a.textContent||'').trim().substring(0,60),href:a.href}}))})()"""
        private fun buttonsJs() = """(function(){return JSON.stringify(Array.from(document.querySelectorAll('button,input[type=submit],.btn,[role=button],a.btn')).map(function(b){return{text:(b.textContent||b.value||'').trim().substring(0,40),tag:b.tagName,id:b.id||'',classes:Array.from(b.classList).join(' ')}}))})()"""
        private fun imagesJs() = """(function(){return JSON.stringify(Array.from(document.querySelectorAll('img[src]')).slice(0,10).map(function(i){return{src:i.src,alt:i.alt||'',w:i.naturalWidth,h:i.naturalHeight}}))})()"""
    }

    private fun noBrowser(): ExecutionResult =
        ExecutionResult.fail("浏览器未就绪，请先打开 MP 浏览器", errorCode = ErrorCodes.ERR_INTERNAL)
}
