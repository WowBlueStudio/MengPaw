// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.fs

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * File system plugin — provides fs.* CLI commands.
 * Pure JVM implementation, zero Android dependencies.
 *
 * SECURITY: All paths are sandboxed to the Agent's workDir.
 * - Symlinks are detected and rejected to prevent escape.
 * - Path traversal (..) is normalized and validated.
 * - Absolute paths outside workDir are blocked.
 * - File reads are capped at 50MB to prevent OOM.
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
        commands = listOf("fs.cat", "fs.ls", "fs.write", "fs.rm", "fs.mkdir", "fs.cp", "fs.mv", "fs.stat", "fs.grep", "fs.glob")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "cat" to ::cat,
        "ls" to ::ls,
        "write" to ::write,
        "rm" to ::rm,
        "mkdir" to ::mkdir,
        "cp" to ::cp,
        "mv" to ::mv,
        "stat" to ::stat,
        "grep" to ::grep,
        "glob" to ::glob
    )

    companion object {
        private const val MAX_READ_SIZE = 50L * 1024 * 1024 // 50MB cap
    }

    // ── Command handlers ──────────────────────────────────────────────

    private suspend fun cat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs cat <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val resolved = resolveSafe(args[0], ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val file = resolved.file
        if (!file.exists()) return ExecutionResult.fail("File not found: ${file.name}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (!file.canRead()) return ExecutionResult.fail("Permission denied", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        return try {
            if (file.length() > MAX_READ_SIZE) {
                return ExecutionResult.fail("File too large: ${formatSize(file.length())} (max ${formatSize(MAX_READ_SIZE)})",
                    errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            ExecutionResult.ok(file.readText())
        } catch (e: Exception) {
            ErrorCollector.report(e, "FsPlugin.cat")
            ExecutionResult.fail("Read error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = if (args.isNotEmpty()) args[0] else "."
        val resolved = resolveSafe(path, ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val dir = resolved.file
        if (!dir.isDirectory) return ExecutionResult.fail("Not a directory", errorCode = ErrorCodes.ERR_INVALID_INPUT)
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
        val resolved = resolveSafe(args[0], ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val content = args.drop(1).joinToString(" ")
        val file = resolved.file
        file.parentFile?.mkdirs()
        return try {
            file.writeText(content)
            ExecutionResult.ok("Written ${content.length} bytes")
        } catch (e: Exception) {
            ErrorCollector.report(e, "FsPlugin.write")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    private suspend fun rm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs rm <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val resolved = resolveSafe(args[0], ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val file = resolved.file
        if (!file.exists()) return ExecutionResult.fail("Not found", errorCode = ErrorCodes.ERR_NOT_FOUND)
        file.deleteRecursively()
        return ExecutionResult.ok("Deleted")
    }

    private suspend fun mkdir(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs mkdir <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val resolved = resolveSafe(args[0], ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        resolved.file.mkdirs()
        return ExecutionResult.ok("Created directory")
    }

    private suspend fun cp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs cp <source> <dest>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val srcResolved = resolveSafe(args[0], ctx)
        val dstResolved = resolveSafe(args[1], ctx)
        if (srcResolved.isFailure) return ExecutionResult.fail("Source: ${srcResolved.error}", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        if (dstResolved.isFailure) return ExecutionResult.fail("Dest: ${dstResolved.error}", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        if (!srcResolved.file.exists()) return ExecutionResult.fail("Source not found", errorCode = ErrorCodes.ERR_NOT_FOUND)
        dstResolved.file.parentFile?.mkdirs()
        return try {
            srcResolved.file.copyTo(dstResolved.file, overwrite = true)
            ExecutionResult.ok("Copied")
        } catch (e: Exception) {
            ErrorCollector.report(e, "FsPlugin.cp")
            ExecutionResult.fail("Copy error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    private suspend fun mv(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs mv <source> <dest>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val srcResolved = resolveSafe(args[0], ctx)
        val dstResolved = resolveSafe(args[1], ctx)
        if (srcResolved.isFailure) return ExecutionResult.fail("Source: ${srcResolved.error}", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        if (dstResolved.isFailure) return ExecutionResult.fail("Dest: ${dstResolved.error}", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        if (!srcResolved.file.exists()) return ExecutionResult.fail("Source not found", errorCode = ErrorCodes.ERR_NOT_FOUND)
        dstResolved.file.parentFile?.mkdirs()
        return try {
            srcResolved.file.renameTo(dstResolved.file)
            ExecutionResult.ok("Moved")
        } catch (e: Exception) {
            ErrorCollector.report(e, "FsPlugin.mv")
            ExecutionResult.fail("Move error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    private suspend fun stat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs stat <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val resolved = resolveSafe(args[0], ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val file = resolved.file
        if (!file.exists()) return ExecutionResult.fail("Not found", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok("""
            Path: ${file.absolutePath}
            Size: ${formatSize(file.length())}
            Is Dir: ${file.isDirectory}
            Readable: ${file.canRead()}
            Writable: ${file.canWrite()}
            Last Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(file.lastModified()))}
        """.trimIndent())
    }

    // ── Search (ported from QwenPaw grep_search / glob_search) ─────────

    /** Content search across files. Usage: fs.grep <pattern> [path] [--regex] [-i] [-n N] */
    private suspend fun grep(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs.grep <pattern> [path] [--regex] [-i] [--context N]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val pattern = args[0]
        val isRegex = args.contains("--regex")
        val caseInsensitive = args.contains("-i")
        val contextLines = args.indexOf("--context").let { idx ->
            if (idx >= 0 && idx + 1 < args.size) args[idx + 1].toIntOrNull() ?: 2 else 2
        }
        val searchPath = args.find { !it.startsWith("-") && it != pattern && it != contextLines.toString() } ?: "."
        val resolved = resolveSafe(searchPath, ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)

        val root = resolved.file
        val results = mutableListOf<String>()
        val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList()
            else listOf(root)

        val regex = try {
            val flags = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            if (isRegex) Regex(pattern, flags) else Regex(Regex.escape(pattern), flags)
        } catch (e: Exception) { return ExecutionResult.fail("Invalid pattern: ${e.message}", errorCode = ErrorCodes.ERR_INVALID_INPUT) }

        var matchCount = 0
        for (file in files) {
            if (matchCount >= 100) { results.add("...(truncated, ${matchCount}+ matches)"); break }
            try {
                val lines = file.readLines()
                for ((i, line) in lines.withIndex()) {
                    if (regex.containsMatchIn(line)) {
                        matchCount++
                        val ctxStart = maxOf(0, i - contextLines)
                        val ctxEnd = minOf(lines.size - 1, i + contextLines)
                        results.add("${file.name}:${i + 1}: ${lines.subList(ctxStart, i).joinToString("\n") { "  |$it" }}")
                        results.add("${file.name}:${i + 1}:> $line")
                        if (ctxEnd > i) results.add(lines.subList(i + 1, ctxEnd + 1).joinToString("\n") { "  |$it" })
                        results.add("")
                    }
                }
            } catch (_: Exception) { /* skip unreadable files */ }
        }
        return ExecutionResult.ok(results.joinToString("\n").ifEmpty { "(no matches for \"$pattern\")" })
    }

    /** File pattern matching. Usage: fs.glob <pattern> [path] */
    private suspend fun glob(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs.glob <pattern> [path]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val pattern = args[0]
        val searchPath = args.getOrElse(1) { "." }
        val resolved = resolveSafe(searchPath, ctx)
        if (resolved.isFailure) return ExecutionResult.fail(resolved.error, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)

        val root = resolved.file
        val regex = try {
            Regex(pattern
                .replace(".", "\\.").replace("*", ".*").replace("?", ".")
                .replace("{", "(").replace("}", ")").replace(",", "|"))
        } catch (e: Exception) { return ExecutionResult.fail("Invalid pattern: ${e.message}", errorCode = ErrorCodes.ERR_INVALID_INPUT) }

        val matches = if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && regex.matches(it.name) }.toList()
        } else { emptyList() }

        return ExecutionResult.ok(
            matches.take(200).joinToString("\n") { f ->
                "${f.relativeTo(root).path} (${formatSize(f.length())})"
            }.ifEmpty { "(no files matching \"$pattern\")" }
        )
    }

    // ── Path Sandbox ──────────────────────────────────────────────────

    /**
     * Container for a safely resolved file path.
     * [isFailure] is true if the path violates the sandbox.
     */
    private data class ResolvedPath(val file: File, val isFailure: Boolean = false, val error: String = "") {
        companion object {
            fun ok(file: File) = ResolvedPath(file, false)
            fun fail(error: String) = ResolvedPath(File("."), true, error)
        }
    }

    /**
     * Resolve and validate a path against the sandbox.
     *
     * Rules:
     * 1. Relative paths are resolved against [ctx.workDir].
     * 2. Absolute paths must be within the workDir subtree.
     * 3. Canonical path (with .. and symlinks resolved) must start with workDir.
     * 4. Symlinks pointing outside the sandbox are rejected.
     */
    private fun resolveSafe(path: String, ctx: ExecutionContext): ResolvedPath {
        // canonicalFile throws IOException if the directory doesn't exist — fall back to absoluteFile
        val workDir = try {
            File(ctx.workDir).canonicalFile
        } catch (e: Exception) {
            File(ctx.workDir).absoluteFile // fallback — sandbox still works with absolute paths
        }
        val rawFile = File(path)
        val absolute = if (rawFile.isAbsolute) rawFile else File(workDir, path)

        return try {
            val canonical = absolute.canonicalFile
            val canonicalPath = canonical.path
            val workDirPath = workDir.path

            // Must be within the workDir sandbox
            if (!canonicalPath.startsWith(workDirPath + File.separator) && canonicalPath != workDirPath) {
                return ResolvedPath.fail("Path outside allowed directory: $path")
            }

            // Detect symlinks that escape the sandbox
            if (canonical.path != absolute.canonicalPath) {
                // If the canonical paths differ, there's a symlink involved.
                // Re-verify the canonical path is still in the sandbox.
                if (!canonical.path.startsWith(workDirPath + File.separator) && canonical.path != workDirPath) {
                    return ResolvedPath.fail("Symlink escape blocked: $path -> ${canonical.path}")
                }
            }

            ResolvedPath.ok(canonical)
        } catch (e: Exception) {
            ResolvedPath.fail("Path resolution error: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
