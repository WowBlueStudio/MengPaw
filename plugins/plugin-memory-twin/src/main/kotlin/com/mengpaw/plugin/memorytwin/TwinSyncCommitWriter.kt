// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.memory.MergeOutcome
import com.mengpaw.kernel.memory.MergeWriteKind
import com.mengpaw.kernel.memory.SnapshotStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.3 同步的"落盘/提交"层 (自 `TwinSyncV3` 拆出, 400 行文件限制) —
 * 合并结果写工作区、提交快照、推进 heads/local、写合并 inbox。
 *
 * 与时序驱动 (HELLO→ACK→TREE_WALK→OBJ_GET) 分开的好处: 这里全是**状态变更**,
 * 单测可以直接断言 refs 与磁盘结果, 不必伪造协议报文。
 */
internal class TwinSyncCommitWriter(
    private val deviceId: String,
    private val agentName: String
) {

    /** 落盘合并结果; 返回 (收到内容数, 冲突数)。 */
    fun applyOutcome(space: SyncSpace, outcome: MergeOutcome, fromDevice: String): TwinSyncV3.SpaceResult {
        var received = 0
        for (write in outcome.writes) {
            when (space.write(write.relPath, write.bytes, fromDevice)) {
                SpaceWriteResult.APPLIED -> if (write.kind == MergeWriteKind.CONTENT) received++
                SpaceWriteResult.SAME -> Unit
                SpaceWriteResult.FAILED -> ErrorCollector.report(
                    java.io.IOException("合并落盘失败: ${space.namespace}/${write.relPath}"),
                    "TwinSyncV3.apply"
                )
            }
        }
        outcome.deletes.forEach { space.delete(it, fromDevice) }
        if (outcome.inboxNotes.isNotEmpty() || outcome.warnings.isNotEmpty() ||
            outcome.conflicts.isNotEmpty()
        ) {
            writeInbox(outcome, fromDevice, space.namespace)
        }
        return TwinSyncV3.SpaceResult(received, outcome.conflicts.size)
    }

    /**
     * 与**某个对端**的合并基准 (§4.5 按对端细化, P0 修复) — 该对端上次与本机一致的那个快照。
     *
     * 绝不退化用命名空间级 `local` 顶替: 那会把"该对端从未有过的文件"判成"对端已删除",
     * 于是删掉本机真实记忆文件 (见 [SnapshotStore.baseRef])。
     * 缺基准 → 空 = §5.2 "无共同基准"分支 (本机为主 + 对端内容 `.conflict` + inbox)。
     */
    fun baseFiles(ns: String, peerKey: String): Map<String, ByteArray> {
        val base = SnapshotStore.baseRef(ns, peerKey) ?: return emptyMap()
        val snapshot = SnapshotStore.snapshot(ns, base) ?: return emptyMap()
        val tree = SnapshotStore.readTree(snapshot.tree) ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        tree.forEach { (path, sha) ->
            SnapshotStore.readBlob(sha)?.let { out[path] = it }
        }
        return out
    }

    /** 本机 heads 里的最大 lamport (合并排序键用; 无快照 → 0)。 */
    fun headLamport(ns: String): Long =
        SnapshotStore.heads(ns).mapNotNull { SnapshotStore.snapshot(ns, it)?.lamport }.maxOrNull() ?: 0L

    /**
     * 造一个合并提交, 并把"与对端的基准"记为**对端候选头** (`baseId`), 不退化成刚造的提交:
     * 对端还没拉到新提交时, 拿它当基准会把对端已删/未有的文件判错。
     */
    fun commitMerge(
        ns: String,
        files: Map<String, String>,
        parents: List<String>,
        peerName: String,
        peerKey: String,
        baseId: String?
    ) {
        val id = SnapshotStore.commit(
            namespace = ns,
            files = files,
            message = "合并 $peerName",
            agent = agentName,
            device = deviceId,
            parents = parents
        )
        SnapshotStore.setLocalRef(ns, id)
        SnapshotStore.setBaseRef(ns, peerKey, baseId ?: id)
    }

    /**
     * 收敛 heads (内容已一致时) — 取**并集**, 不直接采纳对端 heads。
     *
     * 为什么不是单纯采纳: 本地 head 可能不在对端历史里 (双方各自提交后首轮相遇),
     * 直接丢弃会让本地提交变成不可达对象, GC 一跑历史就没了 (内容虽同, 审计链断)。
     * 并集是幂等的: 双方各保留对方 head 后, 下一轮 HELLO 的 heads 集合相等,
     * 自然收敛且不产生新快照 —— 这与"避免互造合并提交"的目标一致。
     *
     * @param baseId      与对端对齐的那个快照 (对端候选头); null 时退化为并集里最新者
     * @param currentTree 当前内容 tree id — local ref 优先指向"内容就是它"的 head
     */
    fun converge(
        ns: String,
        theirHeads: List<String>,
        peerKey: String,
        baseId: String?,
        currentTree: String
    ) {
        val union = (SnapshotStore.heads(ns) + theirHeads).distinct()
        SnapshotStore.setHeads(ns, union)
        val parsed = union.mapNotNull { SnapshotStore.snapshot(ns, it) }
        val local = parsed.firstOrNull { it.tree == currentTree }?.id
            ?: parsed.maxByOrNull { it.lamport }?.id
            ?: union.lastOrNull()
        if (local != null) SnapshotStore.setLocalRef(ns, local)
        val base = baseId ?: local
        if (base != null) SnapshotStore.setBaseRef(ns, peerKey, base)
    }

    /** 冲突/删除提示写 inbox (§5.3) — Agent 直接编辑文件消除冲突标记即可。 */
    private fun writeInbox(outcome: MergeOutcome, peerName: String, ns: String) {
        try {
            val dir = File(DataPaths.AGENTS, "$agentName/inbox")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeNs = ns.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val prefix = if (outcome.conflicts.isEmpty()) "merge_notice" else "merge_conflict"
            val file = File(dir, "${prefix}_${safeNs}_$stamp.md")
            file.writeText(buildString {
                appendLine("# 工作区合并提示 (${outcome.conflicts.size} 个文件)")
                appendLine("> 命名空间: $ns")
                appendLine("> 来自: $peerName")
                appendLine("> 时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine()
                outcome.inboxNotes.forEach { appendLine(it) }
                outcome.warnings.forEach { appendLine("- 告警: $it") }
                appendLine()
                appendLine("请直接编辑这些文件消除冲突标记 (<<<<<<< / ======= / >>>>>>>)，完成后无需额外操作。")
            })
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncV3.writeInbox")
        }
    }
}
