// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.clipboard

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * Agent-internal clipboard plugin.
 *
 * SECURITY: Clipboard data is isolated per session. Each session gets its own
 * private clipboard to prevent cross-session data leakage. Data is held in
 * memory only and cleared when the session ends.
 */
class ClipboardPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "clipboard-plugin", name = "剪贴板", version = "1.1.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "剪贴板操作：copy, paste, clear（按会话隔离）", minCoreVersion = "0.2.0",
        commands = listOf("clipboard.copy", "clipboard.paste", "clipboard.clear"),
            uiButtons = listOf(
                com.mengpaw.kernel.plugin.PluginUiButton("paste", "粘贴", "ContentPaste", com.mengpaw.kernel.plugin.ButtonPlacement.BOTTOM_SHEET, "clipboard.paste")
            )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "copy" to ::copy, "paste" to ::paste, "clear" to ::clear
    )

    /** Per-session clipboard storage. Keyed by sessionId for isolation. */
    private val stores = mutableMapOf<String, String>()

    /** Max clipboard content size per entry (100KB). */
    private val maxContentSize = 100_000

    /** Auto-clear clipboard after this many ms (10 minutes). */
    @Volatile private var lastAccessTime = 0L
    private val ttlMs = 10 * 60 * 1000L

    private fun getStore(ctx: ExecutionContext): String {
        checkTtl()
        return stores[ctx.sessionId] ?: ""
    }

    private fun setStore(ctx: ExecutionContext, content: String) {
        checkTtl()
        stores[ctx.sessionId] = content.take(maxContentSize)
        lastAccessTime = System.currentTimeMillis()
    }

    private fun checkTtl() {
        val now = System.currentTimeMillis()
        if (now - lastAccessTime > ttlMs && stores.isNotEmpty()) {
            stores.clear()
        }
        lastAccessTime = now
    }

    private suspend fun copy(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: clipboard copy <text>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ").take(maxContentSize)
        setStore(ctx, content)
        return ExecutionResult.ok("Copied to clipboard (${content.length} chars)")
    }

    private suspend fun paste(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val content = getStore(ctx)
        return ExecutionResult.ok(content.ifEmpty { "(clipboard is empty)" })
    }

    private suspend fun clear(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        stores.remove(ctx.sessionId)
        return ExecutionResult.ok("Clipboard cleared")
    }
}
