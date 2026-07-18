// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.fs

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginContext
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * File system plugin — provides fs.* CLI commands.
 * Pure JVM implementation, zero Android dependencies.
 */
class FsPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "fs-plugin",
        name = "文件系统",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "文件系统操作：cat, ls, write, rm, mkdir, cp, mv, stat",
        permissions = emptyList(),
        minCoreVersion = "0.2.0",
        commands = listOf("fs.cat", "fs.ls", "fs.write", "fs.rm", "fs.mkdir", "fs.cp", "fs.mv", "fs.stat")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "cat" to ::cat,
        "ls" to ::ls,
        "write" to ::write,
        "rm" to ::rm,
        "mkdir" to ::mkdir,
        "cp" to ::cp,
        "mv" to ::mv,
        "stat" to ::stat
    )

    // ── Command handlers ──────────────────────────────────────────────

    private suspend fun cat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs cat <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = resolvePath(args[0], ctx)
        val file = File(path)
        if (!file.exists()) return ExecutionResult.fail("File not found: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (!file.canRead()) return ExecutionResult.fail("Permission denied: $path", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        return ExecutionResult.ok(file.readText())
    }

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = if (args.isNotEmpty()) resolvePath(args[0], ctx) else ctx.workDir
        val dir = File(path)
        if (!dir.isDirectory) return ExecutionResult.fail("Not a directory: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val listing = dir.listFiles()
            ?.sortedWith(compareBy<File> { it.isFile }.thenBy { it.name })
            ?.joinToString("\n") { file ->
                val type = if (file.isDirectory) "d" else "-"
                val suffix = if (file.isDirectory) "/" else ""
                "$type ${file.name}$suffix (${formatSize(file.length())})"
            } ?: ""
        return ExecutionResult.ok(listing.ifEmpty { "(empty directory)" })
    }

    private suspend fun write(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs write <path> <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = resolvePath(args[0], ctx)
        val content = args.drop(1).joinToString(" ")
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ExecutionResult.ok("Written ${content.length} bytes to $path")
    }

    private suspend fun rm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs rm <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = resolvePath(args[0], ctx)
        val file = File(path)
        if (!file.exists()) return ExecutionResult.fail("Not found: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        file.deleteRecursively()
        return ExecutionResult.ok("Deleted: $path")
    }

    private suspend fun mkdir(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs mkdir <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = resolvePath(args[0], ctx)
        File(path).mkdirs()
        return ExecutionResult.ok("Created directory: $path")
    }

    private suspend fun cp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs cp <source> <dest>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val src = File(resolvePath(args[0], ctx))
        val dst = File(resolvePath(args[1], ctx))
        if (!src.exists()) return ExecutionResult.fail("Source not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return ExecutionResult.ok("Copied ${src.name} to ${dst.name}")
    }

    private suspend fun mv(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs mv <source> <dest>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val src = File(resolvePath(args[0], ctx))
        val dst = File(resolvePath(args[1], ctx))
        if (!src.exists()) return ExecutionResult.fail("Source not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
        return ExecutionResult.ok("Moved ${src.name} to ${dst.name}")
    }

    private suspend fun stat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs stat <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = resolvePath(args[0], ctx)
        val file = File(path)
        if (!file.exists()) return ExecutionResult.fail("Not found: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok("""
            Path: ${file.absolutePath}
            Size: ${formatSize(file.length())}
            Is Dir: ${file.isDirectory}
            Readable: ${file.canRead()}
            Writable: ${file.canWrite()}
            Last Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(file.lastModified()))}
        """.trimIndent())
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun resolvePath(path: String, ctx: ExecutionContext): String {
        val file = File(path)
        return if (file.isAbsolute) file.absolutePath
        else File(ctx.workDir, path).absolutePath
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
