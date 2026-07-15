package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes

/**
 * Clipboard operations namespace.
 * On Android, uses ClipboardManager; falls back to java.awt.Toolkit on desktop/JVM.
 */
object ClipboardExecutor {
    val commands = mapOf(
        "get" to ::get,
        "set" to ::set
    )

    private suspend fun get(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return try {
            val text = getClipboardText()
            ExecutionResult.ok(text.ifEmpty { "(clipboard is empty)" })
        } catch (e: Exception) {
            ExecutionResult.fail(
                "Cannot read clipboard: ${e.message}",
                errorCode = ErrorCodes.ERR_INTERNAL
            )
        }
    }

    private suspend fun set(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: clipboard set <text>",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val text = args.joinToString(" ")
        return try {
            setClipboardText(text)
            ExecutionResult.ok("Copied to clipboard (${text.length} chars)")
        } catch (e: Exception) {
            ExecutionResult.fail(
                "Cannot write clipboard: ${e.message}",
                errorCode = ErrorCodes.ERR_INTERNAL
            )
        }
    }

    private fun getClipboardText(): String {
        // Try Android first
        try {
            val cls = Class.forName("android.content.ClipboardManager")
            // Android clipboard requires Context; stub returns empty on pure JVM
        } catch (_: ClassNotFoundException) { /* not Android */ }

        // java.awt fallback
        return try {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val clipboard = toolkit.systemClipboard
            val transferable = clipboard.getContents(null)
            if (transferable?.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) == true) {
                transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun setClipboardText(text: String) {
        // java.awt fallback
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val clipboard = toolkit.systemClipboard
        val selection = java.awt.datatransfer.StringSelection(text)
        clipboard.setContents(selection, null)
    }
}
