// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
 * - 根文档: soul.md profile.md agents.md boost.md trigger.md heartbeat.md trumanshow.md {date}_dream.md
 * - memory/: memory.md (长期) + memory_{date}.md (中期) + project_*_memory.md + archive.md
 * - 非 .md 白名单 (2026-09-10): twin-model-rules.json (模型能力规则, 见 [SHARED_EXTRA_FILES])
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

    /**
     * 工作区根**允许同步的非 .md 文件** (2026-09-10 新增 `twin-model-rules.json`)。
     *
     * 为什么破例同步一个 JSON: 模型能力规则要保持"跟上 LLM 迭代", 就必须能在任意一台设备上
     * 登记并自动扩散 — Agent 在 A 设备学到 `deepseek-flash` 支持图像理解, 同步后 B/C 设备
     * 的路由立即跟着修正 (**一处学到, 全网共享**)。规则文件本身很小且解析有上限
     * (见 [ModelCapabilityRules.parseExternal]: 条数/正则长度/文件体积三重限制)。
     */
    private val SHARED_EXTRA_FILES = setOf(ModelCapabilityRules.RULES_FILE_NAME)

    /** 清单条目: 相对路径 → 哈希 + 修改时间。 */
    data class ManifestEntry(val hash: String, val mtime: Long)

    /** 清单哈希在协议中的比较形态 (16 字符) — 发送端与接收端必须走同一函数。 */
    const val MANIFEST_HASH_CHARS = 16

    /** 把完整 SHA-256 归一为清单比较形态; 短于上限时原样返回。 */
    fun manifestHash(hash: String): String =
        if (hash.length <= MANIFEST_HASH_CHARS) hash else hash.take(MANIFEST_HASH_CHARS)

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
                // 默认只同步 .md 文档; 根目录白名单文件 (模型能力规则) 例外
                if (!f.name.endsWith(".md") && f.name !in SHARED_EXTRA_FILES) continue
                out[rel] = ManifestEntry(sha256(f), f.lastModified())
            }
        }
    }

    /**
     * 写入一个同步文件: 内容比对 + 原子写 + 冲突检测 (.conflict 备份 + 审计 + inbox 提示)。
     * 返回 "same" 表示内容一致未落盘 (调用方不必计数)。
     */
    fun applyWorkspaceFile(agentName: String, relPath: String, content: String, fromDevice: String, fromTimestamp: Long): String {
        val target = File(DataPaths.AGENTS, "$agentName/$relPath")
        try {
            // P0: 目标存在时只读一次本地内容并复用 — 内容一致就立即返回,
            // 不写 tmp / 不 rename / 不 notifyDocChanged / 不写审计 (避免无意义写盘与提示词缓存失效)。
            val exists = target.exists()
            val localContent: String? = if (exists) try { target.readText() } catch (_: Exception) { null } else null
            if (localContent == content) return "same"

            // 冲突检测 (同旧 applyIdentityUpdate 语义): 本地较新且内容不同 → .conflict 备份, 不覆盖
            // 读取失败时按空内容处理 (= 旧行为), 不回退成"静默覆盖"
            if (exists && target.lastModified() > fromTimestamp && (localContent ?: "") != content) {
                val dateStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val conflictFile = File(target.parent, "${target.name}.conflict.$dateStamp.from_$fromDevice")
                conflictFile.writeText(content)
                android.util.Log.w("MengPawTwin",
                    "工作区文件冲突: $relPath — 本地更新晚于 $fromDevice 的同步,已保存 .conflict 备份")
                writeAudit("WORKSPACE_CONFLICT | from=$fromDevice | file=$relPath | saved=${conflictFile.name}")
                return "conflict"
            }

            // 原子写入 (P2 修复): 原实现先 delete 原文件再 renameTo —
            // renameTo 失败时原文件已丢、tmp 又被后续清理 = 数据双失。
            // 改为: ① 直接 renameTo (POSIX rename 原子替换已有目标, 不预删)
            //       ② 失败才回退 delete+rename (跨文件系统等场景)
            //       ③ 再失败保留 tmp 待恢复并报错, 不删任何数据
            target.parentFile?.mkdirs()
            val tmp = File(target.parent, "${target.name}.tmp")
            tmp.writeText(content)
            var renamed = false
            try { renamed = tmp.renameTo(target) } catch (e: Exception) { ErrorCollector.report(e, "TwinWorkspace.renameTo($relPath)") }
            if (!renamed) {
                if (target.exists()) target.delete()
                renamed = try { tmp.renameTo(target) } catch (e: Exception) { false }
            }
            if (!renamed) {
                ErrorCollector.report(
                    java.io.IOException("原子写失败 — 已保留 tmp 待恢复: $tmp -> $target"),
                    "TwinWorkspace.applyWorkspaceFile($relPath)")
                return "error"
            }
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            // 触发 PromptEngine 缓存失效 (旧 rebuildMemoryDoc 缺失的关键钩子)
            AgentDocs.notifyDocChanged(agentName, target.absolutePath)
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
            AgentDocs.notifyDocChanged(agentName, target.absolutePath)
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
