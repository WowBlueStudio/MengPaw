// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.clipboard

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType

class ClipboardPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "clipboard-plugin", name = "剪贴板", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "剪贴板操作：copy, paste, clear", minCoreVersion = "0.2.0",
        commands = listOf("clipboard.copy", "clipboard.paste", "clipboard.clear")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "copy" to ::copy, "paste" to ::paste, "clear" to ::clear
    )

    private var stored = ""

    private suspend fun copy(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: clipboard copy <text>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        stored = args.joinToString(" ")
        return ExecutionResult.ok("Copied to clipboard (${stored.length} chars)")
    }

    private suspend fun paste(args: List<String>, ctx: ExecutionContext) =
        ExecutionResult.ok(stored.ifEmpty { "(clipboard is empty)" })

    private suspend fun clear(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        stored = ""; return ExecutionResult.ok("Clipboard cleared")
    }
}
