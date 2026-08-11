// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector

/**
 * 表单/元素查询/截图/坐标/视口命令组（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 * 半自动武器 Phase 3 去重后保留: visible/enabled + storage + viewport/userAgent + version。
 * 被 page.* 覆盖的命令 (select/submit/check/uncheck/attr/text/screenshot.element/
 * screenshot.full/coord.click/coord.scroll) 已删除 (决策 #4)。
 */
internal class BrowserQueryCommands(private val ctx: BrowserCommandContext) {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // Element queries
        "visible" to ::visibleCheck, "enabled" to ::enabledCheck,
        // Storage
        "storage" to ::storageOp,
        // Viewport & UA
        "viewport" to ::viewportSet, "userAgent" to ::userAgentOp,
        // Version
        "version" to ::versionCmd
    )
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
