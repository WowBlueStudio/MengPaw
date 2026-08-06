// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector

/**
 * 标签页管理 + 效率命令组（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 * tabs/tab/tab.open/tab.close/tab.all + nav/batch/q。
 */
internal class BrowserTabCommands(private val ctx: BrowserCommandContext) {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "tabs" to ::tabs, "tab" to ::tab, "tab.open" to ::tabOpen,
        "tab.close" to ::tabClose, "tab.all" to ::tabAll,
        "nav" to ::nav, "batch" to ::batch, "q" to ::quick
    )

    // ═══════════════════════════════════════════════════════════════════
    // Tab management
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun tabs(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val all = ctx.tabInfoProvider()
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

    private suspend fun tab(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab <N>  — 切换到标签页 N (0-3)\nbrowser.tabs  — 查看所有标签页",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull() ?: return ExecutionResult.fail("标签页ID必须是数字 0-3", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (id !in 0..3) return ExecutionResult.fail("标签页ID范围: 0-3", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        ctx.tabSwitcher(id)
        return ExecutionResult.ok("已切换到标签页 $id")
    }

    private suspend fun tabOpen(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab.open <N> <url>  — 在标签页N打开URL\nbrowser.tab.open 0 https://example.com",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull()
        if (id == null || args.size < 2) return ExecutionResult.fail(
            "Usage: browser.tab.open <N> <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args.drop(1).joinToString(" ")
        ctx.tabOpener(id, url)
        return ExecutionResult.ok("标签页 $id 正在打开: $url")
    }

    private suspend fun tabClose(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab.close <N>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull() ?: return ExecutionResult.fail("标签页ID必须是数字", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val tabs = ctx.tabInfoProvider()
        if (tabs.size <= 1) return ExecutionResult.fail("至少保留一个标签页")
        if (tabs.none { it.id == id }) return ExecutionResult.fail("标签页 $id 不存在", errorCode = ErrorCodes.ERR_NOT_FOUND)
        ctx.tabCloser(id)
        return ExecutionResult.ok("已关闭标签页 $id")
    }

    /** Extract content from ALL tabs — Agent's most efficient multi-source reading tool. */
    private suspend fun tabAll(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val all = ctx.tabInfoProvider()
        if (all.isEmpty()) return ctx.noBrowser()
        // Switch to each tab and extract content
        val results = mutableListOf<String>()
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        for (t in all) {
            if (!t.isActive) ctx.tabSwitcher(t.id)
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
    private suspend fun nav(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.nav <url>  — 打开URL并自动提取内容", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        val b = ctx.bridge ?: return ctx.noBrowser()
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
    private suspend fun batch(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.batch <cmd1> ;; <cmd2> ;; ...\n每条子命令格式: click|type|scroll|eval|content <args>",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val input = args.joinToString(" ")
        val cmds = input.split(";;").map { it.trim() }.filter { it.isNotEmpty() }
        if (cmds.isEmpty()) return ExecutionResult.fail("无有效命令", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (cmds.size > 10) return ExecutionResult.fail("单次批处理最多10条命令", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val b = ctx.bridge ?: return ctx.noBrowser()
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
    private suspend fun quick(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
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
        val b = ctx.bridge ?: return ctx.noBrowser()
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
    // Quick selector JS snippets
    // ═══════════════════════════════════════════════════════════════════

    private fun searchBoxJs() = """(function(){var s=document.querySelector('input[type=search],input[name=q],input[name=query],input[name=wd],#search,.search input,[role=search] input,[aria-label*=Search]');if(!s)return JSON.stringify({found:false});return JSON.stringify({found:true,tag:s.tagName,type:s.type||'text',id:s.id||'',name:s.name||'',placeholder:s.placeholder||'',selector:(s.id?'#'+s.id:s.name?'[name='+s.name+']':s.tagName.toLowerCase()+'[type='+(s.type||'text')+']')})})()"""
    private fun mainContentJs() = """(function(){var s=['main','article','#content','.post','.article','.main','#main','[role=main]'];for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(el)return JSON.stringify({found:true,selector:s[i],tag:el.tagName,text:(el.textContent||'').trim().substring(0,200)})}return JSON.stringify({found:false,tip:'Try browser.content for full page'})})()"""
    private fun navJs() = """(function(){var s=['nav','#nav','.navbar','.menu','.header','[role=navigation]'];for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(el)return JSON.stringify({found:true,selector:s[i],links:Array.from(el.querySelectorAll('a[href]')).slice(0,15).map(function(a){return{text:(a.textContent||'').trim().substring(0,40),href:a.href}})})}return JSON.stringify({found:false})})()"""
    private fun linksJs() = """(function(){return JSON.stringify(Array.from(document.querySelectorAll('a[href]')).slice(0,20).map(function(a){return{text:(a.textContent||'').trim().substring(0,60),href:a.href}}))})()"""
    private fun buttonsJs() = """(function(){return JSON.stringify(Array.from(document.querySelectorAll('button,input[type=submit],.btn,[role=button],a.btn')).map(function(b){return{text:(b.textContent||b.value||'').trim().substring(0,40),tag:b.tagName,id:b.id||'',classes:Array.from(b.classList).join(' ')}}))})()"""
    private fun imagesJs() = """(function(){return JSON.stringify(Array.from(document.querySelectorAll('img[src]')).slice(0,10).map(function(i){return{src:i.src,alt:i.alt||'',w:i.naturalWidth,h:i.naturalHeight}}))})()"""
}
