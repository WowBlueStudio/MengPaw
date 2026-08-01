// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.mimeTypeFor
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.io.File

/** Clipboard read/write and intent-based actions (open, share, view). */
internal object ClipboardIntentExecutor {

    suspend fun clipboard(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ExecutionResult.ok("Clipboard empty")
        val text = (0 until clip.itemCount).joinToString(" | ") { i ->
            clip.getItemAt(i)?.text?.toString() ?: ""
        }
        return ExecutionResult.ok(text.ifBlank { "Clipboard empty" })
    }

    suspend fun clipboardSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val text = args.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("Usage: sys.clipboard.set <text>")
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MengPaw", text))
        return ExecutionResult.ok("Clipboard set: ${text.take(50)}...")
    }

    suspend fun intentOpen(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val target = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.intent.open <url|package>")
        return try {
            val intent = if (target.startsWith("http://") || target.startsWith("https://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
            } else {
                app.packageManager.getLaunchIntentForPackage(target)
                    ?: return ExecutionResult.fail("Package not found: $target", errorCode = ErrorCodes.ERR_NOT_FOUND)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Opened: $target")
        } catch (e: Exception) {
            ExecutionResult.fail("Open failed: ${e.message}")
        }
    }

    suspend fun intentShare(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val text = args.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("Usage: sys.intent.share <text>")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(Intent.createChooser(intent, "Share via"))
        return ExecutionResult.ok("Share sheet opened")
    }

    suspend fun intentView(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val path = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.intent.view <file_path>")
        val file = File(path)
        if (!file.exists()) return ExecutionResult.fail("File not found: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            return ExecutionResult.ok("Viewing: $path")
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(file), mimeTypeFor(file.name))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
                return ExecutionResult.ok("Viewing (direct): $path")
            } catch (e2: Exception) {
                return ExecutionResult.fail("View failed: ${e2.message}")
            }
        }
    }
}
