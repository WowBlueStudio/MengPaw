// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.browser.bridge.MAX_SCREENSHOT_PIXELS
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector

/**
 * 表单/元素查询/截图/坐标/视口命令组（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 * select/submit/check/uncheck + attr/text/visible/enabled + storage +
 * screenshot.element/full + coord.click/scroll + viewport/userAgent + version。
 */
internal class BrowserQueryCommands(private val ctx: BrowserCommandContext) {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
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
    // Form action commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun selectOpt(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.select <css> <value>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.select(args[0], args[1])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.select"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun submitForm(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.submit <form_selector>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.submit(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.submit"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun checkBox(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.check <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.check(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.check"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun uncheckBox(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.uncheck <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.uncheck(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.uncheck"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Element query commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun attrGet(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.attr <css> <attribute>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.attr(args[0], args[1])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.attr"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun textGet(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.text <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.text(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.text"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun visibleCheck(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.visible <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.visible(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.visible"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun enabledCheck(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.enabled <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.enabled(args[0])) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.enabled"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Storage commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun storageOp(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.storage <local|session> <get|set|clear> [key] [value]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.storage(args[0], args[1], args.getOrNull(2), args.getOrNull(3))) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.storage"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Element screenshot
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun screenshotElement(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.screenshot.element <css>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        return try {
            val safe = args[0].replace("\\", "\\\\").replace("'", "\\'")
            // P0 fix: capturePicture() 已在 API 33+ 移除 (NoSuchMethodError 逃过 catch 必崩)。
            // 新流程: scrollIntoView → 主线程 View.draw 视口 → 用视口坐标 rect 裁剪。
            val scrollLatch = java.util.concurrent.CountDownLatch(1)
            wv.post {
                wv.evaluateJavascript(
                    "(function(){var e=document.querySelector('$safe');if(e)e.scrollIntoView({block:'center'});})()", null
                )
                wv.post { scrollLatch.countDown() }
            }
            scrollLatch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            // 滚动后重新取 rect (getBoundingClientRect 为视口坐标)
            val rectJson = BrowserBridge(wv).eval(
                "(function(){var e=document.querySelector('$safe');if(!e)return JSON.stringify({ok:false,error:'not found'});var r=e.getBoundingClientRect();return JSON.stringify({ok:true,x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)});})()"
            )
            val json = org.json.JSONObject(rectJson)
            if (!json.optBoolean("ok", false)) {
                return ExecutionResult.fail("Element not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
            }
            // 视口截图 (主线程 draw)
            val vpW = wv.width.coerceAtLeast(1)
            val vpH = wv.height.coerceAtLeast(1)
            // P2 fix: 视口位图内存上限防 OOM (与 screenshotFull 同策略) — 超限等比缩小绘制,
            // 元素 rect 同步缩放, 返回 rect 即实际位图坐标 (与图一致)
            val scale = if (vpW.toLong() * vpH > MAX_SCREENSHOT_PIXELS)
                kotlin.math.sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / (vpW.toLong() * vpH)).toFloat()
            else 1f
            val bmpW = (vpW * scale).toInt().coerceAtLeast(1)
            val bmpH = (vpH * scale).toInt().coerceAtLeast(1)
            val fullBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
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
    private suspend fun screenshotFullCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (!BuiltinBrowserPlugin.quickClickEnabled()) return ExecutionResult.fail(
            "Quick Click 已禁用。请在浏览器设置→智能体协同中开启。\nQuick Click is disabled. Enable in Settings → Agent Collaboration.",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )
        val b = ctx.bridge ?: return ctx.noBrowser()
        val maxH = args.firstOrNull()?.toIntOrNull() ?: BuiltinBrowserPlugin.screenshotMaxHeight()
        return try {
            val result = b.screenshotFull(maxH)
            val r = ExecutionResult.ok(result)
            // Append follow-up hint for Agent
            ExecutionResult.ok(result + "\n---\n使用 browser.coord.click <x> <y> 在此截图坐标上点击。参考: skill.run browser-control")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.screenshotFull"); ExecutionResult.fail("${e.message}\n可降级: browser.screenshot (视口截图)", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Tap at absolute page coordinates (from screenshotFull image). */
    private suspend fun coordClickCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (!BuiltinBrowserPlugin.quickClickEnabled()) return ExecutionResult.fail(
            "Quick Click 已禁用。请在浏览器设置→智能体协同中开启。",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.coord.click <x> <y>\nCoordinates are relative to the full-page screenshot from browser.screenshot.full", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val x = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid X coordinate", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid Y coordinate", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.coordClick(x, y)) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.coordClick"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Scroll to full-page y-coordinate (for verification before clicking). */
    private suspend fun coordScrollCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.coord.scroll <y>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid Y coordinate", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.coordScroll(y)) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.coordScroll"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Viewport and User-Agent
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun viewportSet(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.viewport <width> <height>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val w = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid width", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val h = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid height", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        return try {
            wv.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');if(m){m.setAttribute('content','width=$w,height=$h,initial-scale=1');}else{m=document.createElement('meta');m.name='viewport';m.content='width=$w,height=$h,initial-scale=1';document.head.appendChild(m);}})()", null)
            ExecutionResult.ok("Viewport set to ${w}x${h}")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.viewport"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun userAgentOp(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
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

    private suspend fun versionCmd(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        // 版本号不硬编码 — 随 gradle defaultConfig.versionName (BuildConfig.VERSION_NAME) 自动同步
        return ExecutionResult.ok("MP Browser v${com.mengpaw.browser.BuildConfig.VERSION_NAME} / Android SDK ${android.os.Build.VERSION.SDK_INT}")
    }
}
