// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.memory.TreeMerge
import com.mengpaw.kernel.memory.TwinFileClass
import com.mengpaw.kernel.memory.WorkspaceSnapshotSource
import java.io.File
import java.security.MessageDigest

/**
 * 孪生工作区同步 (v0.22.0 → P2 分类化)。
 *
 * 同步范围来自内核 [WorkspaceSnapshotSource] (P1 唯一实现, 本对象不再重复扫描):
 * - 收录: 任意层级 `.md` + 根目录 `twin-model-rules.json`;
 * - 排除: `inbox/` `dialog/` `memory/backup/`、`CLI.md`、`*.tmp`、`*.conflict.*` (S 类)。
 *
 * P2 起按设计 §2.1 把收录文件分成 L / M / D / S 四类 ([classify], 委托 kernel [TreeMerge]),
 * `twin-model-rules.json` 单列 RULES。分类决定 v0.3 合并策略 (见 `TreeMerge`)。
 */
object TwinWorkspace {

    /** 清单条目: 相对路径 → 哈希 + 修改时间。 */
    data class ManifestEntry(val hash: String, val mtime: Long)

    /** 清单哈希在协议中的比较形态 (16 字符) — 发送端与接收端必须走同一函数。 */
    const val MANIFEST_HASH_CHARS = 16

    /** 把完整 SHA-256 归一为清单比较形态; 短于上限时原样返回。 */
    fun manifestHash(hash: String): String =
        if (hash.length <= MANIFEST_HASH_CHARS) hash else hash.take(MANIFEST_HASH_CHARS)

    /**
     * 相对路径消毒 — **写盘入口必经** (P0)。
     *
     * 入站 handler 与出站响应解析都会把对端给的 relPath 送进 [applyWorkspaceBytes] /
     * [removeWorkspaceFile]，所以消毒必须放在存储层而不是只放在消息处理层。
     * 拒绝: 空白 / 绝对路径 / 盘符或 URL scheme / 任何 `..` 段。
     */
    internal fun sanitizeRelPath(relPath: String): String? {
        if (relPath.isBlank()) return null
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return null
        if (relPath.contains(":")) return null  // Windows 盘符/URL scheme 保险
        if (relPath.split('/', '\\').any { it == ".." }) return null
        return relPath
    }

    /** 扫描工作区, 生成 v0.2 协议清单 (扫描委托 kernel 实现, 消除重复)。
     *  条目哈希为**完整 SHA-256**; 线上形态由 [manifestHash] 归一 (接收端两种形态都认)。 */
    fun buildManifest(agentName: String): Map<String, ManifestEntry> {
        val result = LinkedHashMap<String, ManifestEntry>()
        WorkspaceSnapshotSource.scan(agentName).forEach { file ->
            result[file.relPath] = ManifestEntry(sha256(file.bytes), file.mtime)
        }
        return result
    }

    /** 文件类别 (§2.1) — L 长驻 / M 中期 / D 设备库存 / RULES 规则 / S 短期。 */
    fun classify(relPath: String): TwinFileClass = TreeMerge.classify(relPath)

    /** 按类别分组的当前工作区文件 (S 类已被扫描排除, 不会出现)。 */
    fun classifyWorkspace(agentName: String): Map<TwinFileClass, List<String>> {
        val grouped = LinkedHashMap<TwinFileClass, MutableList<String>>()
        buildManifest(agentName).keys.sorted().forEach { relPath ->
            grouped.getOrPut(classify(relPath)) { mutableListOf() }.add(relPath)
        }
        return grouped
    }

    /** 文本写入口 (v0.2 路径与既有调用方) — 委托字节版本。 */
    fun applyWorkspaceFile(agentName: String, relPath: String, content: String, fromDevice: String, fromTimestamp: Long): String =
        applyWorkspaceBytes(agentName, relPath, content.toByteArray(Charsets.UTF_8), fromDevice, fromTimestamp)

    /**
     * 写入一个同步文件 (字节): 路径消毒 + 内容比对 + 原子写 + 冲突检测。
     *
     * 返回:
     * - `"applied"`  已写入
     * - `"same"`     内容一致未落盘 (调用方不必计数)
     * - `"conflict"` 本地较新且内容不同 → 已写 `.conflict` 备份, 未覆盖
     * - `"rejected"` relPath 非法 (对端可控, 越界直接拒绝)
     * - `"error"`    读/写失败 (含"目标存在但读不出来" — 不拿空内容去覆盖)
     */
    fun applyWorkspaceBytes(
        agentName: String,
        relPath: String,
        content: ByteArray,
        fromDevice: String,
        fromTimestamp: Long
    ): String {
        // P0: relPath 来自对端响应 (send / pull 的 key), 存储层再消毒一次, 不信任调用方
        val safeRel = sanitizeRelPath(relPath) ?: run {
            writeAudit("WORKSPACE_REJECT | from=$fromDevice | path=$relPath")
            return "rejected"
        }
        val target = File(DataPaths.AGENTS, "$agentName/$safeRel")
        try {
            // P0: 目标存在时只读一次本地内容并复用 — 内容一致就立即返回,
            // 不写 tmp / 不 rename / 不 notifyDocChanged / 不写审计 (避免无意义写盘与提示词缓存失效)。
            val exists = target.exists()
            val local: ByteArray? = if (exists) try { target.readBytes() } catch (_: Exception) { null } else null
            // P1: 目标存在却读不出来 (权限 / IO / 是目录) → 明确失败, 绝不拿空内容覆盖
            if (exists && local == null) {
                ErrorCollector.report(
                    java.io.IOException("工作区文件不可读, 拒绝覆盖: $target"),
                    "TwinWorkspace.applyWorkspaceBytes($safeRel)")
                return "error"
            }
            if (local != null && local.contentEquals(content)) return "same"

            // 冲突检测: 本地较新且内容不同 → .conflict 备份, 不覆盖
            if (exists && target.lastModified() > fromTimestamp) {
                val dateStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val conflictFile = File(target.parent, "${target.name}.conflict.$dateStamp.from_$fromDevice")
                conflictFile.writeBytes(content)
                android.util.Log.w("MengPawTwin",
                    "工作区文件冲突: $relPath — 本地更新晚于 $fromDevice 的同步,已保存 .conflict 备份")
                writeAudit("WORKSPACE_CONFLICT | from=$fromDevice | file=$relPath | saved=${conflictFile.name}")
                return "conflict"
            }

            // 原子写入 (P2 修复 + P1 加固):
            //   ① tmp 名带唯一后缀 — 两个同步协程同时写同一文件时不会互踩同一个 tmp;
            //   ② 主路径直接 renameTo (POSIX rename 原子替换, 不预删目标);
            //   ③ 回退改为 copyTo(overwrite) — 旧实现先 delete 目标, 并发下会删掉别人刚写入的唯一副本;
            //   ④ 再失败保留 tmp 待恢复并报错, 不删任何数据。
            target.parentFile?.mkdirs()
            val tmp = File(target.parent, "${target.name}.${System.nanoTime()}.tmp")
            tmp.writeBytes(content)
            var renamed = false
            try { renamed = tmp.renameTo(target) } catch (e: Exception) { ErrorCollector.report(e, "TwinWorkspace.renameTo($safeRel)") }
            if (!renamed) {
                renamed = try {
                    tmp.copyTo(target, overwrite = true)
                    true
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinWorkspace.copyTo($safeRel)")
                    false
                }
            }
            if (!renamed) {
                ErrorCollector.report(
                    java.io.IOException("原子写失败 — 已保留 tmp 待恢复: $tmp -> $target"),
                    "TwinWorkspace.applyWorkspaceBytes($safeRel)")
                return "error"
            }
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            // 触发 PromptEngine 缓存失效 (旧 rebuildMemoryDoc 缺失的关键钩子)
            AgentDocs.notifyDocChanged(agentName, target.absolutePath)
            writeAudit("WORKSPACE_SYNC | from=$fromDevice | file=$safeRel")
            return "applied"
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinWorkspace.applyWorkspaceBytes($safeRel)")
            return "error"
        }
    }

    /** 删除对端已不存在的同步文件 (本地保留 .deleted 备份, 防误删)。 */
    fun removeWorkspaceFile(agentName: String, relPath: String, fromDevice: String) {
        // 同 applyWorkspaceFile: 删除也走对端路径, 入口必须消毒
        val safeRel = sanitizeRelPath(relPath) ?: run {
            writeAudit("WORKSPACE_REJECT | from=$fromDevice | path=$relPath")
            return
        }
        val target = File(DataPaths.AGENTS, "$agentName/$safeRel")
        if (!target.exists()) return
        try {
            val backup = File(target.parent, "${target.name}.deleted.${fromDevice}")
            val moved = if (!backup.exists()) target.renameTo(backup) else { target.delete(); true }
            if (!moved) {
                ErrorCollector.report(
                    java.io.IOException("删除失败: $target"),
                    "TwinWorkspace.removeWorkspaceFile($safeRel)")
                return
            }
            AgentDocs.notifyDocChanged(agentName, target.absolutePath)
            writeAudit("WORKSPACE_REMOVE | from=$fromDevice | file=$safeRel")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinWorkspace.removeWorkspaceFile($safeRel)")
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
    fun fileHash(file: File): String = try {
        sha256(file.readBytes())
    } catch (e: Exception) {
        ErrorCollector.report(e, "TwinWorkspace.sha256(${file.name})")
        "0"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
