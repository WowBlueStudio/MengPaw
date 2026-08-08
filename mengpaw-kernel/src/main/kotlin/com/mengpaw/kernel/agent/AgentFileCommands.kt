// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.namespace.NotifyBus

/**
 * agent.* 文件命令执行器 — read/write/ls/rm/mkdir/output (拆自 AgentExecutor,
 * 400 行文件拆分)。只依赖 DataPaths/AgentDocs/NotifyBus, 无文档管理器依赖。
 */
internal class AgentFileCommands {

    /** Resolve the effective agent name, falling back to default. */
    private fun agentName(ctx: ExecutionContext) = ctx.agentName ?: "agent"

    // ── File I/O (built-in, no plugin needed) ──────────────────────

    /**
     * Paths the Agent may NEVER write to — protects APK core files, system binaries.
     * Reading from these paths is allowed (Agent needs to inspect its own config/docs).
     *
     * Strategy: deny-list, NOT allow-list. Agent can access everything except:
     * - Non-data system partitions (/system, /vendor)
     * - App private binaries outside its workspace
     */
    private val WRITE_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", // installed APKs
        "/data/dalvik-cache/",
        // P1 修复: 应用私有数据 (插件 AAR、会话库等) — agent 不可写
        "/data/data/", "/data/user/"
    )

    /** Paths blocked from deletion — extends write blocked with critical agent files. */
    private val RM_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", "/data/dalvik-cache/",
        // P1 修复: 应用私有数据 — agent 不可删
        "/data/data/", "/data/user/",
        // Core agent files — use dedicated commands (agent.memory.rm, etc.) instead
        // soul.md, profile.md, agents.md are deletable via agent.rm (Agent owns them)
    )

    /**
     * Resolve path with traversal protection (canonical path resolves ../ and symlinks).
     * 相对路径以 Agent 工作区 {AGENTS}/{agent}/ 为基准 — 提示词教的工作区相对语义
     * (agent.read profile.md) 由此成为现实。
     * 前导 "/" 宽容 (FIX 自检报告 P0-2): Agent 常按 Unix 习惯写 "/Agent文档/MengPaw",
     * Android 上被 File.isAbsolute 当根目录绝对路径 → 必然不存在。字面解析失败时,
     * 去掉前导 / 按工作区重试; 真实系统绝对路径 (/data/...) 存在时不受影响。
     */
    private fun resolvePath(raw: String, agent: String): java.io.File? {
        val trimmed = raw.trim()
        val file = if (java.io.File(trimmed).isAbsolute) java.io.File(trimmed)
                   else java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$agent/$trimmed")
        val canonical = try { file.canonicalFile } catch (_: Exception) { null }
        if ((canonical == null || !canonical.exists()) && trimmed.startsWith("/") && trimmed.length > 1) {
            val retry = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$agent/${trimmed.trimStart('/')}")
            val retryCanonical = try { retry.canonicalFile } catch (_: Exception) { null }
            if (retryCanonical != null && retryCanonical.exists()) return retryCanonical
        }
        return canonical
    }

    /** agent.read <path> — read any file (no restrictions beyond filesystem). */
    internal suspend fun readFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.read <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.joinToString(" ")
        val file = resolvePath(path, agentName(ctx))
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!file.exists()) return ExecutionResult.fail(
            // FIX(自检报告 P0-2): 输出解析后的真实路径 — Agent 盲试时能据此修正基准
            "文件不存在: $path (解析为 ${file.absolutePath})\n" +
            "工作区根: ${com.mengpaw.kernel.DataPaths.AGENTS}/${agentName(ctx)} — 相对路径以它为基准",
            errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (file.isDirectory) {
            val listing = file.listFiles()?.take(50)?.joinToString("\n") { f ->
                "${if (f.isDirectory) "📁" else "📄"} ${f.name} (${if (f.isFile) "${f.length()}B" else "-"})"
            } ?: "(空目录)"
            return ExecutionResult.ok("$path:\n$listing")
        }
        return try {
            val content = file.readText().take(100_000)
            ExecutionResult.ok(content)
        } catch (e: Exception) {
            ExecutionResult.fail("读取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.ls <path> — list files in a directory. Defaults to workspace root. */
    internal suspend fun listFiles(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val defaultPath = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agent"
        val path = if (args.isEmpty()) defaultPath else args.joinToString(" ")
        val dir = resolvePath(path, agent) ?: return ExecutionResult.fail("路径无效: $path")
        if (!dir.exists()) return ExecutionResult.fail(
            "路径不存在: $path (解析为 ${dir.absolutePath})\n" +
            "工作区根: ${com.mengpaw.kernel.DataPaths.AGENTS}/$agent — 相对路径以它为基准")
        if (!dir.isDirectory) {
            // Single file — show its info
            return ExecutionResult.ok("📄 ${dir.name} — ${dir.length()}B — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(dir.lastModified()))}")
        }
        val files = dir.listFiles()?.sortedBy { if (it.isDirectory) 0 else 1 } ?: emptyList()
        if (files.isEmpty()) return ExecutionResult.ok("$path/\n(空目录)")
        return ExecutionResult.ok(buildString {
            appendLine("$path/")
            files.forEach { f ->
                val icon = if (f.isDirectory) "📁" else "📄"
                val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
                appendLine("  $icon ${f.name}$size · $date")
            }
            appendLine()
            appendLine("${files.size} 个项目")
        })
    }

    /** agent.rm <path> — delete a file or empty directory. Blocked on system paths. Requires --force for files. */
    internal suspend fun deleteFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = args.filter { it.startsWith("--") }
        val pathArgs = args.filter { !it.startsWith("--") }
        val force = flags.contains("--force")
        if (pathArgs.isEmpty()) return ExecutionResult.fail(
            "用法: agent.rm <path> [--force]\n" +
            "删除文件或空目录。文件需要 --force 确认（不可逆）。系统路径受保护。\n" +
            "先预览: agent.ls <path> 查看要删的文件。"
        )
        val path = pathArgs.joinToString(" ")
        val file = resolvePath(path, agentName(ctx)) ?: return ExecutionResult.fail("路径无效: $path")
        val canonical = file.absolutePath
        if (!file.exists()) return ExecutionResult.fail("文件不存在: $path")
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) {
            return ExecutionResult.fail("目录非空: $path (${file.listFiles()?.size ?: 0} 个项目)。\n请先删除目录中的文件，或用 agent.memory.mid.delete 删除中期记忆分片。")
        }
        if (file.isFile && !force) {
            val isOutput = canonical.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT)
            return ExecutionResult.fail(buildString {
                appendLine("⚠️ 即将永久删除文件: $path (${formatSize(file.length())})")
                appendLine()
                appendLine("此操作不可逆。确认删除请执行: agent.rm $path --force")
                if (isOutput) {
                    appendLine()
                    appendLine("⚠️ 此文件在输出目录中，删除后用户将无法在文件管理器中找到它。")
                    appendLine("建议先确认用户是否需要此文件再删除。")
                }
            })
        }
        if (RM_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止删除系统/应用目录: $path")
        }
        return try {
            val size = file.length()
            val ok = file.delete()
            if (ok) ExecutionResult.ok("已删除: $path (${formatSize(size)})\n\n如需恢复，从孪生设备同步: twin.sync")
            else ExecutionResult.fail("删除失败: $path")
        } catch (e: Exception) {
            ExecutionResult.fail("删除异常: ${e.message}")
        }
    }

    /** agent.mkdir <path> — create a directory. */
    internal suspend fun makeDir(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.mkdir <path>")
        val path = args.joinToString(" ")
        val dir = resolvePath(path, agentName(ctx)) ?: return ExecutionResult.fail("路径无效: $path")
        if (dir.exists()) return ExecutionResult.fail("已存在: $path")
        return try {
            dir.mkdirs()
            ExecutionResult.ok("已创建目录: $path")
        } catch (e: Exception) {
            ExecutionResult.fail("创建失败: ${e.message}")
        }
    }

    /** agent.output — 显示输出目录。HTML/MD/PDF 等用户文档写出到此目录，文件管理器可访问。 */
    internal suspend fun output(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val dir = java.io.File(com.mengpaw.kernel.DataPaths.OUTPUT)
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles()?.sortedBy { if (it.isDirectory) 0 else 1 } ?: emptyList()
        return ExecutionResult.ok(buildString {
            appendLine("📂 输出目录: ${com.mengpaw.kernel.DataPaths.OUTPUT}")
            appendLine("   状态: ${if (dir.canWrite()) "可写" else "⚠️ 不可写"}")
            val totalSize = files.sumOf { it.length() }
            if (totalSize > 0) appendLine("   总大小: ${formatSize(totalSize)}")
            appendLine()
            if (files.isEmpty()) {
                appendLine("(空)")
            } else {
                files.forEach { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                    appendLine("  $icon ${f.name}$size")
                }
                appendLine()
                appendLine("${files.size} 个项目")
            }
            appendLine()
            appendLine("写文件: agent.write <路径> <内容>")
            appendLine("示例: agent.write ${com.mengpaw.kernel.DataPaths.OUTPUT}/report.html <html内容>")
        })
    }

    /** P2-11(自检报告): 引用/转义规则 — 内容含空格用引号包裹, 多行/大段用 --from 从文件导入。 */
    private val WRITE_USAGE = buildString {
        appendLine("用法: agent.write <路径> <内容>")
        appendLine("  - 内容含空格: 用引号包裹 → agent.write a.md \"Hello World\"")
        appendLine("  - 多行/大段内容: 从文件导入 → agent.write a.md --from 草稿.md")
    }

    /** --from 导入源文件体积上限 (防 OOM — 5MB 足覆盖日常草稿/报告)。 */
    private val MAX_FROM_BYTES = 5 * 1024 * 1024

    /**
     * agent.write <path> <content> — write file. Blocked on system/app paths only.
     * P2-11(自检报告): 支持 `--from <源文件>` 从文件导入多行/大段内容 (UTF-8);
     * 引用规则: 内容含空格用引号包裹, 多行用 --from。
     */
    internal suspend fun writeFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val fromIdx = args.indexOf("--from")
        val sourcePath = if (fromIdx >= 0 && fromIdx + 1 < args.size) args[fromIdx + 1] else null
        val pathArgs = if (fromIdx >= 0) args.filterIndexed { i, _ -> i != fromIdx && i != fromIdx + 1 } else args

        val path = pathArgs.firstOrNull()?.trim().orEmpty()
        if (path.isBlank()) return ExecutionResult.fail(WRITE_USAGE, errorCode = ErrorCodes.ERR_INVALID_INPUT)

        // 内容来源: --from 读源文件 (读宽松: 只需存在且非目录 — 与 agent.read 同级策略)
        val content = if (sourcePath != null) {
            val src = resolvePath(sourcePath, agentName(ctx))
                ?: return ExecutionResult.fail("源路径无效: $sourcePath", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            if (!src.exists()) return ExecutionResult.fail(
                "源文件不存在: $sourcePath (解析为 ${src.absolutePath})",
                errorCode = ErrorCodes.ERR_NOT_FOUND)
            if (src.isDirectory) return ExecutionResult.fail(
                "源路径是目录: $sourcePath — 请指定文件", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            if (src.length() > MAX_FROM_BYTES) return ExecutionResult.fail(
                "源文件过大: ${src.length() / 1024}KB (上限 ${MAX_FROM_BYTES / 1024}KB)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            try {
                src.readText()
            } catch (e: Exception) {
                return ExecutionResult.fail("读取源文件失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
            }
        } else {
            if (pathArgs.size < 2) return ExecutionResult.fail(WRITE_USAGE, errorCode = ErrorCodes.ERR_INVALID_INPUT)
            pathArgs.drop(1).joinToString(" ")
        }

        val file = resolvePath(path, agentName(ctx))
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        // Deny-list check: block writes to system/app partitions
        val canonical = file.path
        if (WRITE_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止写入系统/应用目录: $path", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            file.parentFile?.mkdirs()
            // 标准原子写: tmp 写好后 Files.move(REPLACE_EXISTING) 覆盖 — 失败保留原文件
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
            }
            // 仅对系统提示词中的三个缓存文件触发精确失效
            val wsRoot = "${com.mengpaw.kernel.DataPaths.AGENTS}/${agentName(ctx)}"
            val cachedDocs = setOf("agents.md", "soul.md", "memory/memory.md")
            if (canonical.startsWith(wsRoot) && cachedDocs.any { canonical.endsWith("/$it") }) {
                com.mengpaw.kernel.agent.AgentDocs.notifyDocChanged(agentName(ctx), canonical)
            }
            val isOutput = canonical.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT)
            val msg = buildString {
                append("已写入: $path (${content.length} 字符, ${content.lines().size} 行)")
                if (sourcePath != null) append(" ← $sourcePath")
                // P0 (2026-08-08 自检): 回传内容预览 — Agent 声称成功必须基于真实落盘内容
                if (content.isNotBlank()) {
                    append("\n\n内容预览 (前 200 字符):\n")
                    append(content.take(200))
                }
                if (isOutput) {
                    append("\n\n📱 用户可在文件管理器的 ${com.mengpaw.kernel.DataPaths.OUTPUT} 找到此文件")
                }
            }
            if (isOutput) {
                try {
                    NotifyBus.message("📄 Agent 生成了文件: ${file.name} (${formatSize(file.length())})")
                } catch (_: Exception) {}
            }
            ExecutionResult.ok(msg)
        } catch (e: Exception) {
            val isOutput = canonical.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT)
            val errMsg = buildString {
                append("写入失败: ${e.message}")
                if (isOutput) append("\n输出目录: ${com.mengpaw.kernel.DataPaths.OUTPUT}")
            }
            ExecutionResult.fail(errMsg, errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}

/** 人类可读文件大小 (storageReport/listFiles/writeFile/deleteFile 共用)。 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
}

/** 目录递归体积 (agent.storage 用)。 */
internal fun dirSize(dir: java.io.File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    dir.listFiles()?.forEach {
        total += if (it.isDirectory) dirSize(it) else it.length()
    }
    return total
}
