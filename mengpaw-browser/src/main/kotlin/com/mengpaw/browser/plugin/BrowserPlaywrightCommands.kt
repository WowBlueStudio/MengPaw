// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.browser.util.BrowserStorage
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import org.json.JSONObject

/**
 * Playwright 语义命令组（半自动武器方案 Phase 1）— `page.*` 与 `browser.*` 并存过渡。
 *
 * 命令名/参数对齐 Playwright（LLM 零学习成本），截图只回路径（决策 #3），
 * 超长页按段返回 + 坐标按段拆分（决策 #5），存储权限拒绝时每次提示重授（决策 #6）。
 * Phase 3 去重后 `page.*` 覆盖的 `browser.*` 命令删除，本组为最终命令面。
 */
internal class BrowserPlaywrightCommands(private val ctx: BrowserCommandContext) {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "page.goto" to ::gotoCmd,
        "page.load" to ::loadCmd,
        "page.screenshot" to ::screenshotCmd,
        "page.click" to ::clickCmd,
        "page.fill" to ::fillCmd,
        "page.content" to ::contentCmd,
        "page.text" to ::textCmd,
        "page.attr" to ::attrCmd,
        "page.wait_selector" to ::waitSelectorCmd,
        "page.scroll" to ::scrollCmd,
        "page.scroll_by" to ::scrollByCmd,
        "page.eval" to ::evalCmd,
        "page.url" to ::urlCmd,
        "page.title" to ::titleCmd,
        "page.back" to ::backCmd,
        "page.forward" to ::forwardCmd,
        "page.select" to ::selectCmd,
        "page.submit" to ::submitCmd,
        "page.check" to ::checkCmd,
        "page.uncheck" to ::uncheckCmd,
        "page.screenshot.element" to ::screenshotElementCmd,
        "page.key" to ::keyCmd
    )

    // ── 参数解析: 位置参数 + --flag 值 + 布尔 flag ──────────────────────

    private val VALUE_FLAGS = setOf("wait", "max-height", "grep", "head", "tail", "timeout")

    private class Parsed(
        val positional: List<String>,
        private val flags: Map<String, String?>
    ) {
        fun has(name: String): Boolean = flags.containsKey(name)
        fun str(name: String, def: String? = null): String? = flags[name] ?: def
        fun int(name: String, def: Int): Int = flags[name]?.toIntOrNull() ?: def
    }

    private fun parse(args: List<String>): Parsed {
        val positional = mutableListOf<String>()
        val flags = mutableMapOf<String, String?>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-i" -> { flags["i"] = null; i++ }
                a.startsWith("--") -> {
                    val name = a.removePrefix("--")
                    val next = args.getOrNull(i + 1)
                    if (name in VALUE_FLAGS && next != null && !next.startsWith("--")) {
                        flags[name] = next; i += 2
                    } else { flags[name] = null; i++ }
                }
                else -> { positional.add(a); i++ }
            }
        }
        return Parsed(positional, flags)
    }

    // ── 导航 ────────────────────────────────────────────────────────────

    private suspend fun gotoCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val p = parse(args)
        val url = p.positional.firstOrNull()
            ?: return ExecutionResult.fail("Usage: page.goto <url> [--wait domcontentloaded|networkidle]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val wait = p.str("wait", "domcontentloaded")
        if (wait != "domcontentloaded" && wait != "networkidle") {
            return ExecutionResult.fail("--wait 仅支持 domcontentloaded|networkidle", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            val r = JSONObject(b.goto(url, wait))
            if (r.optBoolean("ok", false)) {
                ExecutionResult.ok("## page.goto 完成\nURL: $url\n标题: ${ctx.webViewProvider()?.title ?: ""}\n等待模式: $wait")
            } else {
                ExecutionResult.fail("加载超时: ${r.optString("error")}", errorCode = ErrorCodes.ERR_TIMEOUT)
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "PageCommands.goto")
            ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** 半自动合体: goto + 精确等待 + 分段全页截图 + 坐标系统（决策 #1/#5）。 */
    private suspend fun loadCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val p = parse(args)
        val url = p.positional.firstOrNull()
            ?: return ExecutionResult.fail("Usage: page.load <url> [--max-height N]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val maxH = p.int("max-height", BuiltinBrowserPlugin.screenshotMaxHeight())
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            val gotoR = JSONObject(b.goto(url, "domcontentloaded"))
            val title = ctx.webViewProvider()?.title ?: ""
            if (!gotoR.optBoolean("ok", false)) {
                return ExecutionResult.fail("导航失败: ${gotoR.optString("error")}", errorCode = ErrorCodes.ERR_TIMEOUT)
            }
            // 决策 #6: 拒绝授权 → 每次 page.load 提示重授，不落盘
            if (!BrowserStorage.hasStorageAccess()) {
                return ExecutionResult.fail(
                    "## page.load 导航完成但截图未落盘\nURL: $url\n标题: $title\n\n" +
                        "⚠️ 存储权限未授予（Agent 无法读取截图）。请在系统设置中授予 MP 浏览器「所有文件访问」后重试 page.load。",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED
                )
            }
            val cap = JSONObject(b.screenshotFullSegments(maxH))
            if (!cap.optBoolean("ok", false)) {
                return ExecutionResult.fail("截图失败: ${cap.optString("error")}", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            val sb = StringBuilder("## page.load 完成\nURL: $url\n标题: $title\n")
            sb.append("段数: ${cap.optInt("segmentCount")} (partial: ${cap.optBoolean("partial")})\n")
            val segs = cap.optJSONArray("segments")
            if (segs != null) {
                for (i in 0 until segs.length()) {
                    val s = segs.getJSONObject(i)
                    sb.append("段 ${s.optInt("seg")}: ${s.optString("path")} (${s.optInt("width")} × ${s.optInt("height")}, 缩放 ${s.optDouble("scale")})\n")
                }
            }
            sb.append("坐标系统: page.click <seg> <x> <y> 按段内截图坐标，框架自动还原为页面坐标")
            ExecutionResult.ok(sb.toString())
        } catch (e: Exception) {
            ErrorCollector.report(e, "PageCommands.load")
            ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun screenshotCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val p = parse(args)
        val b = ctx.bridge ?: return ctx.noBrowser()
        if (!BrowserStorage.hasStorageAccess()) {
            return ExecutionResult.fail("⚠️ 存储权限未授予（Agent 无法读取截图）。请在系统设置中授予「所有文件访问」后重试。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            if (p.has("full")) {
                val cap = JSONObject(b.screenshotFullSegments(BuiltinBrowserPlugin.screenshotMaxHeight()))
                if (!cap.optBoolean("ok", false)) {
                    ExecutionResult.fail("全页截图失败: ${cap.optString("error")}", errorCode = ErrorCodes.ERR_INTERNAL)
                } else {
                    ExecutionResult.ok(cap.toString())
                }
            } else {
                ExecutionResult.ok(b.screenshot())
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "PageCommands.screenshot")
            ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun clickCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val nums = args.mapNotNull { it.toIntOrNull() }
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            val r = when {
                args.size == 3 && nums.size == 3 -> b.coordClickSegment(nums[0], nums[1], nums[2])
                args.size == 2 && nums.size == 2 -> b.coordClickSegment(1, nums[0], nums[1])
                args.size == 1 -> b.click(args[0])
                else -> return ExecutionResult.fail("Usage: page.click <seg> <x> <y> | page.click <x> <y> | page.click <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            ExecutionResult.ok(r)
        } catch (e: Exception) {
            ErrorCollector.report(e, "PageCommands.click")
            ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── 表单与查询 ──────────────────────────────────────────────────────

    private suspend fun fillCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: page.fill <css> <text>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.fastType(args[0], args.drop(1).joinToString(" "))) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.fill"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun textCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.text <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.text(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.text"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun attrCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: page.attr <css> <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.attr(args[0], args[1])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.attr"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun waitSelectorCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val p = parse(args)
        val css = p.positional.firstOrNull()
            ?: return ExecutionResult.fail("Usage: page.wait_selector <css> [--timeout N]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.waitForSelector(css, p.int("timeout", 5000))) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.waitSelector"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** 提取正文 + 内置过滤（--grep/--regex/-i/--head/--tail，参照 fs.grep）。 */
    private suspend fun contentCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val p = parse(args)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            val j = JSONObject(b.contentRaw())
            val lines = j.optString("text").split("\n")
            var filtered = lines
            p.str("grep")?.let { grep ->
                val ci = p.has("i")
                if (p.has("regex")) {
                    val pattern = Regex(grep, if (ci) setOf(RegexOption.IGNORE_CASE) else emptySet())
                    filtered = lines.filter { pattern.containsMatchIn(it) }
                } else {
                    filtered = lines.filter { it.contains(grep, ignoreCase = ci) }
                }
            }
            p.int("head", -1).takeIf { it >= 0 }?.let { filtered = filtered.take(it) }
            p.int("tail", -1).takeIf { it >= 0 }?.let { filtered = filtered.takeLast(it) }
            val out = filtered.joinToString("\n").ifBlank { "(无匹配)" }
            ExecutionResult.ok("## page.content\n标题: ${j.optString("title")}\nURL: ${j.optString("url")}\n文本行: ${filtered.size}\n---\n$out")
        } catch (e: Exception) {
            ErrorCollector.report(e, "PageCommands.content")
            ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun selectCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: page.select <css> <value>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.select(args[0], args[1])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.select"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun submitCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.submit <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.submit(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.submit"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun checkCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.check <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.check(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.check"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun uncheckCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.uncheck <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.uncheck(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.uncheck"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun screenshotElementCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.screenshot.element <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        if (!BrowserStorage.hasStorageAccess()) {
            return ExecutionResult.fail("⚠️ 存储权限未授予（Agent 无法读取截图）。请在系统设置中授予「所有文件访问」后重试。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try { ExecutionResult.ok(b.screenshotElement(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.screenshotElement"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun keyCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.key <key>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.key(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.key"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ── 滚动 / JS / 信息 / 历史 ─────────────────────────────────────────

    private suspend fun scrollCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val p = parse(args)
        val x = p.positional.getOrNull(0)?.toIntOrNull() ?: 0
        val y = p.positional.getOrNull(1)?.toIntOrNull() ?: 0
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.eval("window.scrollTo($x,$y);JSON.stringify({ok:true,x:window.scrollX,y:window.scrollY})")) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.scroll"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun scrollByCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val dy = args.firstOrNull()?.toIntOrNull() ?: 0
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.scroll(0f, dy.toFloat())) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.scrollBy"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun evalCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: page.eval <js>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.eval(args.joinToString(" "))) }
        catch (e: Exception) { ErrorCollector.report(e, "PageCommands.eval"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun urlCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        return ExecutionResult.ok(wv.url ?: "(无 URL)")
    }

    private suspend fun titleCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        return ExecutionResult.ok(wv.title ?: "(无标题)")
    }

    private suspend fun backCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            if (!wv.canGoBack()) ExecutionResult.fail("无历史可回退", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else { wv.post { wv.goBack() }; ExecutionResult.ok(b.waitForLoad("domcontentloaded", 15000)) }
        } catch (e: Exception) { ErrorCollector.report(e, "PageCommands.back"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun forwardCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            if (!wv.canGoForward()) ExecutionResult.fail("无历史可前进", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else { wv.post { wv.goForward() }; ExecutionResult.ok(b.waitForLoad("domcontentloaded", 15000)) }
        } catch (e: Exception) { ErrorCollector.report(e, "PageCommands.forward"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
}
