package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes

/**
 * Clipboard operations namespace.
 * Uses reflection to access platform clipboard (Android or desktop AWT).
 */
object ClipboardExecutor {
    val commands = mapOf(
        "get" to ::get,
        "set" to ::set
    )

    private suspend fun get(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return try {
            val text = readClipboard()
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
            writeClipboard(text)
            ExecutionResult.ok("Copied to clipboard (${text.length} chars)")
        } catch (e: Exception) {
            ExecutionResult.fail(
                "Cannot write clipboard: ${e.message}",
                errorCode = ErrorCodes.ERR_INTERNAL
            )
        }
    }

    private fun readClipboard(): String {
        return try {
            val toolkitClass = Class.forName("java.awt.Toolkit")
            val toolkit = toolkitClass.getMethod("getDefaultToolkit").invoke(null)
            val clipboard = toolkitClass.getMethod("getSystemClipboard").invoke(toolkit)
            val contents = clipboard.javaClass.getMethod("getContents", Any::class.java).invoke(clipboard, null)
            if (contents != null) {
                val flavorClass = Class.forName("java.awt.datatransfer.DataFlavor")
                val flavorField = flavorClass.getField("stringFlavor")
                val stringFlavor = flavorField.get(null)
                val supported = contents.javaClass.getMethod("isDataFlavorSupported", flavorClass)
                    .invoke(contents, stringFlavor) as? Boolean ?: false
                if (supported) {
                    val data = contents.javaClass.getMethod("getTransferData", flavorClass)
                        .invoke(contents, stringFlavor)
                    data as? String ?: ""
                } else ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeClipboard(text: String) {
        val toolkitClass = Class.forName("java.awt.Toolkit")
        val toolkit = toolkitClass.getMethod("getDefaultToolkit").invoke(null)
        val clipboard = toolkitClass.getMethod("getSystemClipboard").invoke(toolkit)
        val selectionClass = Class.forName("java.awt.datatransfer.StringSelection")
        val selection = selectionClass.getConstructor(String::class.java).newInstance(text)
        clipboard.javaClass.getMethod("setContents", selectionClass, Any::class.java)
            .invoke(clipboard, selection, null)
    }
}
