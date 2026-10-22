// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `memory.*` 命名空间 (P1) — 工作区快照历史与回滚。
 *
 * 命令:
 * ```
 * memory.commit [-m 说明]         — 对当前工作区打快照 (无变化不产生新快照)
 * memory.log [n]                  — 最近 n 个快照 (时间/设备/变更文件数/说明)
 * memory.show <path> [snapshot]   — 某文件在某快照的内容 (缺省最近快照)
 * memory.diff [--to <snapshot>]   — 当前工作区 vs 快照的 unified diff
 * memory.restore <path> [snap]    — 把 .md 文件恢复成快照内容 (MID, 需 reason)
 * memory.gc [--keep 30d]          — 清理不可达对象, 回报删除数与库体积
 * ```
 *
 * 与 `agent.memory.*` (三轨记忆) 区分: 本命名空间是**工作区文件的历史版本**,
 * 不是记忆条目。对象库/快照纯 JVM, 不接协议 (P2 才接传输)。
 */
object MemoryHistoryCommands {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "commit" to ::commit,
        "log" to ::log,
        "show" to ::show,
        "diff" to ::diff,
        "restore" to ::restore,
        "gc" to ::gc
    )

    private const val DEFAULT_LOG_LIMIT = 10
    private const val MAX_LOG_LIMIT = 100
    private const val DEFAULT_KEEP_DAYS = 30
    private const val DEFAULT_KEEP_COUNT = 200
    private const val SNAPSHOT_SCAN_LIMIT = 500

    // ── memory.commit ───────────────────────────────────────────────

    private suspend fun commit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val ns = namespaceOf(agent)
        val files = WorkspaceSnapshotSource.snapshotFiles(agent)
        val tree = SnapshotStore.writeTree(files)
        val tips = (SnapshotStore.heads(ns) + listOfNotNull(SnapshotStore.localRef(ns))).distinct()
        val tipTrees = tips.mapNotNull { SnapshotStore.snapshot(ns, it)?.tree }
        if (tree in tipTrees) {
            val tip = tips.first()
            return ExecutionResult.ok("无变化 — 工作区与最近快照 ${tip.take(8)} 一致 (${files.size} 个文件)")
        }
        val message = flagValue(args, "message", "m")?.trim()?.takeIf { it.isNotEmpty() } ?: "手动快照"
        val id = SnapshotStore.commit(ns, files, message, agent, deviceId(ctx))
        return ExecutionResult.ok("已创建快照 ${id.take(8)} — ${files.size} 个文件, 说明: $message")
    }

    // ── memory.log ──────────────────────────────────────────────────

    private suspend fun log(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val ns = namespaceOf(agentName(ctx))
        val requested = positional(args).firstOrNull()?.toIntOrNull() ?: DEFAULT_LOG_LIMIT
        val limit = requested.coerceIn(1, MAX_LOG_LIMIT)
        val list = SnapshotStore.history(ns, limit)
        if (list.isEmpty()) {
            return ExecutionResult.ok("暂无快照 — 用 memory.commit 创建第一个快照")
        }
        return ExecutionResult.ok(buildString {
            appendLine("最近快照 (${list.size}):")
            list.forEach { snap ->
                appendLine(
                    "  ${snap.shortId}  ${formatTime(snap.wall)}  " +
                        "${snap.device.ifBlank { "local" }}  " +
                        "${changedFileCount(ns, snap)}文件  ${snap.message.take(60)}"
                )
            }
        }.trimEnd())
    }

    // ── memory.show ─────────────────────────────────────────────────

    private suspend fun show(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pos = positional(args)
        val rawPath = pos.getOrNull(0)
            ?: return ExecutionResult.fail(
                "用法: memory.show <path> [snapshot] — 查看某文件在某快照的内容",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val agent = agentName(ctx)
        val ns = namespaceOf(agent)
        val snap = resolveSnapshot(ns, pos.getOrNull(1))
            ?: return ExecutionResult.fail("快照不存在: ${pos.getOrNull(1) ?: "(最近)"} — 用 memory.log 查看可用快照")
        val relPath = relativePath(agent, rawPath)
            ?: return ExecutionResult.fail("路径不在当前工作区: $rawPath")
        val tree = SnapshotStore.readTree(snap.tree)
            ?: return ExecutionResult.fail("快照 ${snap.shortId} 的清单缺失或损坏")
        val blobId = tree[relPath]
            ?: return ExecutionResult.fail("快照 ${snap.shortId} 中没有 $relPath")
        val bytes = SnapshotStore.readBlob(blobId)
            ?: return ExecutionResult.fail("对象缺失或损坏: ${blobId.take(12)}")
        return ExecutionResult.ok(String(bytes, Charsets.UTF_8))
    }

    // ── memory.diff ─────────────────────────────────────────────────

    private suspend fun diff(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val ns = namespaceOf(agent)
        val toRef = flagValue(args, "to")
        val snap = resolveSnapshot(ns, toRef)
            ?: return ExecutionResult.fail("快照不存在: ${toRef ?: "(最近)"} — 用 memory.log 查看可用快照")
        val tree = SnapshotStore.readTree(snap.tree)
            ?: return ExecutionResult.fail("快照 ${snap.shortId} 的清单缺失或损坏")

        val oldTexts = LinkedHashMap<String, String>()
        tree.forEach { (path, blobId) ->
            SnapshotStore.readBlob(blobId)?.let { oldTexts[path] = String(it, Charsets.UTF_8) }
        }
        val newTexts = WorkspaceSnapshotSource.readTexts(agent)

        val paths = (oldTexts.keys + newTexts.keys).sorted()
        val body = StringBuilder()
        for (path in paths) {
            val old = oldTexts[path] ?: ""
            val new = newTexts[path] ?: ""
            if (old == new) continue
            val piece = TextDiff.diff(old, new, "a/$path", "b/$path")
            if (piece.isNotEmpty()) body.append(piece).append('\n')
        }
        if (body.isEmpty()) {
            return ExecutionResult.ok("无差异 — 工作区与快照 ${snap.shortId} 一致")
        }
        return ExecutionResult.ok(
            "对比基准: 快照 ${snap.shortId} (${formatTime(snap.wall)})\n\n" + body.toString().trimEnd()
        )
    }

    // ── memory.restore ──────────────────────────────────────────────

    private suspend fun restore(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val pos = positional(args)
        val rawPath = pos.getOrNull(0)
            ?: return ExecutionResult.fail(
                "用法: memory.restore <path> [snapshot] — 恢复旧版本 (MID 风险, 需附 reason 意图声明)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        if (!rawPath.endsWith(".md", ignoreCase = true)) {
            return ExecutionResult.fail("memory.restore 只恢复 .md 文件 — $rawPath 不是 Markdown")
        }
        val agent = agentName(ctx)
        val ns = namespaceOf(agent)
        val snap = resolveSnapshot(ns, pos.getOrNull(1))
            ?: return ExecutionResult.fail("快照不存在: ${pos.getOrNull(1) ?: "(最近)"} — 用 memory.log 查看可用快照")
        val relPath = relativePath(agent, rawPath)
            ?: return ExecutionResult.fail("路径不在当前工作区: $rawPath")
        val tree = SnapshotStore.readTree(snap.tree)
            ?: return ExecutionResult.fail("快照 ${snap.shortId} 的清单缺失或损坏")
        val blobId = tree[relPath]
            ?: return ExecutionResult.fail("快照 ${snap.shortId} 中没有 $relPath — 无法恢复")
        val bytes = SnapshotStore.readBlob(blobId)
            ?: return ExecutionResult.fail("对象缺失或损坏: ${blobId.take(12)}")

        val target = File(DataPaths.resolver.agentDir(agent), relPath)
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.restore.tmp")
            tmp.writeBytes(bytes)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // 回退: 复制覆盖, 绝不先删目标
                Files.copy(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                try { tmp.delete() } catch (_: Exception) {}
            }
            AgentDocs.notifyDocChanged(agent, target.absolutePath)
            ExecutionResult.ok("已恢复 $relPath ← 快照 ${snap.shortId} (${bytes.size} 字节)")
        } catch (e: Exception) {
            ErrorCollector.report(e, "MemoryHistoryCommands.restore($relPath)")
            ExecutionResult.fail("恢复失败: ${e.message ?: e::class.simpleName}")
        }
    }

    // ── memory.gc ───────────────────────────────────────────────────

    private suspend fun gc(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val ns = namespaceOf(agentName(ctx))
        val keepArg = flagValue(args, "keep")
        val keepDays = if (keepArg == null) {
            DEFAULT_KEEP_DAYS
        } else {
            parseDays(keepArg) ?: return ExecutionResult.fail(
                "参数非法: --keep 需要天数 (如 30d / 30)", errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val removed = SnapshotStore.gc(ns, keepDays, DEFAULT_KEEP_COUNT)
        return ExecutionResult.ok(
            "已清理 $removed 个不可达对象 — 对象库 ${SnapshotStore.objectCount()} 个对象, " +
                formatSize(SnapshotStore.storeSizeBytes()) +
                " (保留 ${keepDays}天/${DEFAULT_KEEP_COUNT}快照, 取宽)"
        )
    }

    // ── 共用助手 ────────────────────────────────────────────────────

    private fun agentName(ctx: ExecutionContext): String =
        ctx.agentName?.trim()?.takeIf { it.isNotEmpty() } ?: "agent"

    /** 工作区命名空间 — `ws-<agentName>` (存储层负责路径消毒)。 */
    private fun namespaceOf(agent: String): String = "ws-$agent"

    /** P1 无安装期设备 UUID (P2 落地); 宿主可经环境变量注入, 缺省 local。 */
    private fun deviceId(ctx: ExecutionContext): String =
        ctx.environment["deviceId"]?.trim()?.takeIf { it.isNotEmpty() } ?: "local"

    private fun resolveSnapshot(ns: String, ref: String?): Snapshot? {
        val history = SnapshotStore.history(ns, SNAPSHOT_SCAN_LIMIT)
        if (ref.isNullOrBlank() || ref.equals("head", ignoreCase = true)) return history.firstOrNull()
        return history.firstOrNull { it.id == ref } ?: history.firstOrNull { it.id.startsWith(ref) }
    }

    private fun changedFileCount(ns: String, snap: Snapshot): Int {
        val current = SnapshotStore.readTree(snap.tree) ?: return 0
        val parentTree = snap.parents.firstOrNull()?.let { SnapshotStore.snapshot(ns, it)?.tree } ?: return current.size
        val previous = SnapshotStore.readTree(parentTree) ?: return current.size
        val added = (current.keys - previous.keys).size
        val removed = (previous.keys - current.keys).size
        val modified = current.keys.intersect(previous.keys).count { current[it] != previous[it] }
        return added + removed + modified
    }

    /** 解析位置参数 (跳过 `--flag value` 对)。 */
    private fun positional(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            if (token.startsWith("--")) {
                i += if (i + 1 < args.size && !args[i + 1].startsWith("--")) 2 else 1
            } else {
                out.add(token)
                i++
            }
        }
        return out
    }

    /** 读取 flag 值 (长/短两种写法)。 */
    private fun flagValue(args: List<String>, longName: String, shortName: String? = null): String? {
        val longIdx = args.indexOf("--$longName")
        val shortIdx = shortName?.let { name -> args.indexOf("-$name") } ?: -1
        val idx = if (longIdx >= 0) longIdx else shortIdx
        if (idx < 0) return null
        return args.getOrNull(idx + 1)?.takeIf { !it.startsWith("-") }
    }

    private fun parseDays(raw: String): Int? {
        val match = Regex("^(\\d+)d?$", RegexOption.IGNORE_CASE).find(raw.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceAtLeast(0)
    }

    /** 把用户给的路径归一为工作区相对路径; 越界/空白 → null。 */
    private fun relativePath(agent: String, raw: String): String? {
        val input = raw.trim().replace('\\', '/')
        if (input.isEmpty()) return null
        val root = try { File(DataPaths.resolver.agentDir(agent)).canonicalFile } catch (_: Exception) { return null }
        val candidate = if (File(input).isAbsolute) File(input) else File(root, input)
        val canonical = try { candidate.canonicalFile } catch (_: Exception) { return null }
        if (canonical == root) return null
        if (!canonical.path.startsWith(root.path + File.separator)) return null
        return canonical.relativeTo(root).path.replace('\\', '/')
    }

    private fun formatTime(wall: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(wall))

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
