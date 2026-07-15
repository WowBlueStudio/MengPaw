package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult

/**
 * File system operations namespace.
 */
object FsExecutor {
    val commands = mapOf(
        "cat" to ::cat,
        "ls" to ::ls,
        "write" to ::write,
        "rm" to ::rm,
        "mkdir" to ::mkdir,
        "cp" to ::cp,
        "mv" to ::mv,
        "stat" to ::stat
    )

    private suspend fun cat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs cat <path>")
        val path = resolvePath(args[0], ctx)
        val file = java.io.File(path)
        if (!file.exists()) return ExecutionResult.fail("File not found: $path")
        if (!file.canRead()) return ExecutionResult.fail("Permission denied: $path")
        return ExecutionResult.ok(file.readText())
    }

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = if (args.isNotEmpty()) resolvePath(args[0], ctx) else ctx.workDir
        val dir = java.io.File(path)
        if (!dir.isDirectory) return ExecutionResult.fail("Not a directory: $path")
        val listing = dir.listFiles()
            ?.sortedWith(compareBy<java.io.File> { it.isFile }.thenBy { it.name })
            ?.joinToString("\n") { file ->
                val type = if (file.isDirectory) "d" else "-"
                "$type ${if (file.isDirectory) file.name else file.name} (${formatSize(file.length())})"
            } ?: ""
        return ExecutionResult.ok(listing.ifEmpty { "(empty directory)" })
    }

    private suspend fun write(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs write <path> <content>")
        val path = resolvePath(args[0], ctx)
        val content = args.drop(1).joinToString(" ")
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ExecutionResult.ok("Written ${content.length} bytes to $path")
    }

    private suspend fun rm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs rm <path>")
        val path = resolvePath(args[0], ctx)
        val file = java.io.File(path)
        if (!file.exists()) return ExecutionResult.fail("Not found: $path")
        file.deleteRecursively()
        return ExecutionResult.ok("Deleted: $path")
    }

    private suspend fun mkdir(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs mkdir <path>")
        val path = resolvePath(args[0], ctx)
        java.io.File(path).mkdirs()
        return ExecutionResult.ok("Created directory: $path")
    }

    private suspend fun cp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs cp <source> <dest>")
        val src = java.io.File(resolvePath(args[0], ctx))
        val dst = java.io.File(resolvePath(args[1], ctx))
        if (!src.exists()) return ExecutionResult.fail("Source not found: ${args[0]}")
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return ExecutionResult.ok("Copied ${src.name} to ${dst.name}")
    }

    private suspend fun mv(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: fs mv <source> <dest>")
        val src = java.io.File(resolvePath(args[0], ctx))
        val dst = java.io.File(resolvePath(args[1], ctx))
        if (!src.exists()) return ExecutionResult.fail("Source not found: ${args[0]}")
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
        return ExecutionResult.ok("Moved ${src.name} to ${dst.name}")
    }

    private suspend fun stat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: fs stat <path>")
        val path = resolvePath(args[0], ctx)
        val file = java.io.File(path)
        if (!file.exists()) return ExecutionResult.fail("Not found: $path")
        return ExecutionResult.ok(
            """
            Path: ${file.absolutePath}
            Size: ${formatSize(file.length())}
            Is Dir: ${file.isDirectory}
            Readable: ${file.canRead()}
            Writable: ${file.canWrite()}
            Last Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(file.lastModified()))}
            """.trimIndent()
        )
    }

    private fun resolvePath(path: String, ctx: ExecutionContext): String {
        val file = java.io.File(path)
        return if (file.isAbsolute) file.absolutePath
        else java.io.File(ctx.workDir, path).absolutePath
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
