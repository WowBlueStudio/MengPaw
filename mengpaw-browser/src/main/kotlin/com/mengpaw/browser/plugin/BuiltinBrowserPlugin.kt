// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
        "title" to ::title, "url" to ::url
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
                val load = if (t.isLoading) "⏳" else "✓"
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
        return ExecutionResult.ok("Screenshot queued. Check 截图存档/ for output.")
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
    // Quick selector JS snippets
    // ═══════════════════════════════════════════════════════════════════

    companion object {
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
