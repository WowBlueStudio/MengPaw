// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * 快照引用 (refs) 存储 (P1, docs/memory-git-sync-design.md §3.1) — 自 [SnapshotStore] 拆出。
 *
 * ```
 * {AGENTS}/twin/store/refs/<ns>/
 *   heads         ← 一行一个 sha (集合, 分叉未合并时多个)
 *   local         ← 本机上次成功同步头 (历史/GC 种子; **不再**当合并 base)
 *   base-<peer>   ← 与**该对端**的合并基准 (P0: 网状多对端必须按对端隔离)
 * ```
 *
 * 全部写入走原子写 (tmp + rename), 读取失败按空处理并记 [ErrorCollector] —— ref 损坏的后果
 * 是"历史看起来是空的"，绝不抛给调用方。
 */
internal object SnapshotRefs {

    /** 对端基准 ref 前缀 — `refs/<ns>/base-<peer>`。 */
    const val BASE_PREFIX = "base-"
    /** 对端名片段上限 (防超长文件名 / 路径超限)。 */
    private const val MAX_PEER_KEY_CHARS = 64
    private val REF_UNSAFE = Regex("[^\\p{L}\\p{N}_-]")

    fun heads(namespace: String): List<String> = read(namespace, "heads")

    fun local(namespace: String): String? = read(namespace, "local").firstOrNull()

    fun setLocal(namespace: String, id: String) {
        if (ObjectStore.isObjectId(id)) save(namespace, "local", listOf(id))
    }

    fun setHeads(namespace: String, ids: List<String>) {
        save(namespace, "heads", ids.filter { ObjectStore.isObjectId(it) }.distinct())
    }

    /**
     * 与**某个对端**的合并基准 (§4.5 的按对端细化, P0 修复)。
     *
     * 为什么必须按对端: `local` 的语义是"本机上次成功同步头", 而同步是网状多对端 —
     * 拿命名空间级 `local` 当第三端的第一份基准, 会把"该对端从来没有过的文件"误判成
     * "对端已删除", 进而删掉本机真实记忆文件并把删除传播给其他端
     * (plugin `TwinThirdPeerMergeTest` 回归)。缺该对端基准 = 与它从未同步过 → 走 §5.2
     * "无共同基准"分支 (本机为主 + 对端内容 `.conflict` + inbox), 绝不删本机文件。
     *
     * @return 基准快照 id; 未与该对端同步过 → null
     */
    fun base(namespace: String, peerKey: String): String? =
        read(namespace, "$BASE_PREFIX${safePeer(peerKey)}").firstOrNull()

    fun setBase(namespace: String, peerKey: String, id: String) {
        if (ObjectStore.isObjectId(id)) save(namespace, "$BASE_PREFIX${safePeer(peerKey)}", listOf(id))
    }

    /** 全部对端基准 — GC 种子用 (基准快照可能已不在 heads 里, 清掉会让下一轮退化成无基准合并)。 */
    fun bases(namespace: String): List<String> {
        val files = try {
            refDir(namespace).listFiles()?.filter { it.isFile && it.name.startsWith(BASE_PREFIX) }
        } catch (e: Exception) {
            ErrorCollector.report(e, "SnapshotRefs.bases($namespace)")
            null
        } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                file.readLines().firstOrNull()?.trim()
            } catch (e: Exception) {
                ErrorCollector.report(e, "SnapshotRefs.bases.read(${file.name})")
                null
            }
        }.filter { ObjectStore.isObjectId(it) }.distinct()
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private fun read(namespace: String, name: String): List<String> {
        val file = refFile(namespace, name)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().map { it.trim() }
                .filter { ObjectStore.isObjectId(it) }.distinct()
        } catch (e: Exception) {
            ErrorCollector.report(e, "SnapshotRefs.read($namespace/$name)")
            emptyList()
        }
    }

    private fun save(namespace: String, name: String, ids: List<String>) {
        val file = refFile(namespace, name)
        try {
            file.parentFile?.mkdirs()
            val suffix = if (ids.isEmpty()) "" else "\n"
            ObjectStore.writeTextAtomic(file, ids.joinToString("\n", postfix = suffix))
        } catch (e: Exception) {
            ErrorCollector.report(e, "SnapshotRefs.save($namespace/$name)")
        }
    }

    private fun refFile(namespace: String, name: String): File = File(refDir(namespace), name)

    /** 命名空间 ref 目录 — 命名空间片段消毒后作为单层目录名。 */
    private fun refDir(namespace: String): File {
        val safe = namespace.replace(REF_UNSAFE, "_").ifBlank { "default" }
        return File(File(DataPaths.TWIN_STORE, "refs"), safe)
    }

    /** 对端名 → ref 文件名安全片段 (与命名空间同一套消毒规则, 限长防超长文件名)。 */
    private fun safePeer(peerKey: String): String =
        peerKey.replace(REF_UNSAFE, "_").take(MAX_PEER_KEY_CHARS).ifBlank { "peer" }
}
