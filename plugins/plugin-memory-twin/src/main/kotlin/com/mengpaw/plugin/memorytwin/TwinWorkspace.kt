// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.security.MessageDigest

/**
 * 孪生工作区同步 (v0.22.0) — 替代哈希链账本。
 *
 * 同步单元 = 工作区文档文件 (整个 {agent}/ 目录, 排除 CLI.md/inbox/dialog/backup):
 * - 根文档: soul.md profile.md agents.md boost.md trigger.md HEARTBEAT.md {date}_dream.md
 * - memory/: memory.md (长期) + memory_{date}.md (中期) + project_*_memory.md + archive.md
 * - 排除: CLI.md (Android 操作指南, 无需跨设备) / inbox/ (本地任务队列) /
 *         dialog/ (本地对话流) / memory/backup/ (本机安全副本) / *.tmp / *.conflict.*
 *
 * 收敛机制: manifest (相对路径 + SHA-256 + mtime) 交换 → 哈希比对 → 差异传输 →
 * LWW 冲突检测 (.conflict 备份, 同旧 applyIdentityUpdate 样板)。
 */
object TwinWorkspace {

    /** 不同步的目录名 (相对工作区根)。 */
    private val EXCLUDED_DIRS = setOf("inbox", "dialog", "backup")

    /** 不同步的文件名 (工作区根)。 */
    private val EXCLUDED_FILES = setOf("CLI.md")

    /** 清单条目: 相对路径 → 哈希 + 修改时间。 */
    data class ManifestEntry(val hash: String, val mtime: Long)

    /** 扫描工作区, 生成同步清单。 */
    fun buildManifest(agentName: String): Map<String, ManifestEntry> {
        val root = File(DataPaths.AGENTS, agentName)
        if (!root.exists()) return emptyMap()
        val result = LinkedHashMap<String, ManifestEntry>()
        scanDir(root, "", result)
        return result
    }

    private fun scanDir(dir: File, relDir: String, out: MutableMap<String, ManifestEntry>) {
        val files: List<File> = try { dir.listFiles()?.sortedBy { it.name } ?: emptyList() } catch (_: Exception) { emptyList() }
        for (f in files) {
            val rel = if (relDir.isEmpty()) f.name else "$relDir/${f.name}"
            if (f.isDirectory) {
                if (f.name in EXCLUDED_DIRS) continue
                scanDir(f, rel, out)
            } else {
                if (f.name in EXCLUDED_FILES) continue
                if (f.name.endsWith(".tmp")) continue
                if (f.name.contains(".conflict.")) continue
                if (!f.name.endsWith(".md")) continue
                out[rel] = ManifestEntry(sha256(f), f.lastModified())
            }
        }
    }

    /** 写入一个同步文件: 原子写 + 冲突检测 (.conflict 备份 + 审计 + inbox 提示)。 */
    fun applyWorkspaceFile(agentName: String, relPath: String, content: String, fromDevice: String, fromTimestamp: Long): String {
        val target = File(DataPaths.AGENTS, "$agentName/$relPath")
        try {
            // 冲突检测 (同旧 applyIdentityUpdate 语义): 本地较新且内容不同 → .conflict 备份, 不覆盖
            if (target.exists() && target.lastModified() > fromTimestamp) {
                val localContent = try { target.readText() } catch (_: Exception) { "" }
                if (localContent != content) {
                    val dateStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val conflictFile = File(target.parent, "${target.name}.conflict.$dateStamp.from_$fromDevice")
                    conflictFile.writeText(content)
                    android.util.Log.w("MengPawTwin",
                        "工作区文件冲突: $relPath — 本地更新晚于 $fromDevice 的同步,已保存 .conflict 备份")
                    writeAudit("WORKSPACE_CONFLICT | from=$fromDevice | file=$relPath | saved=${conflictFile.name}")
                    return "conflict"
                }
            }

            // 原子写入
            target.parentFile?.mkdirs()
            val tmp = File(target.parent, "${target.name}.tmp")
            tmp.writeText(content)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            // 触发 PromptEngine 缓存失效 (旧 rebuildMemoryDoc 缺失的关键钩子)
            AgentDocs.onDocChanged?.invoke(agentName, target.absolutePath)
            writeAudit("WORKSPACE_SYNC | from=$fromDevice | file=$relPath")
            return "applied"
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinWorkspace.applyWorkspaceFile($relPath)")
            return "error"
        }
    }

    /** 删除对端已不存在的同步文件 (本地保留 .deleted 备份, 防误删)。 */
    fun removeWorkspaceFile(agentName: String, relPath: String, fromDevice: String) {
        val target = File(DataPaths.AGENTS, "$agentName/$relPath")
        if (!target.exists()) return
        try {
            val backup = File(target.parent, "${target.name}.deleted.${fromDevice}")
            if (!backup.exists()) target.renameTo(backup)
            else target.delete()
            AgentDocs.onDocChanged?.invoke(agentName, target.absolutePath)
            writeAudit("WORKSPACE_REMOVE | from=$fromDevice | file=$relPath")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinWorkspace.removeWorkspaceFile($relPath)")
        }
    }

    private fun writeAudit(line: String) {
        try {
            val auditFile = File(DataPaths.TWIN_AUDIT)
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(
                "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} | $line\n"
            )
        } catch (_: Exception) {}
    }

    /** SHA-256 文件哈希。 */
    fun fileHash(file: File): String = sha256(file)

    private fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinWorkspace.sha256(${file.name})")
            "0"
        }
    }
}
