// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.acp.PeerAgent
import com.mengpaw.kernel.acp.SyncV3Messages
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.memory.MergeOutcome
import com.mengpaw.kernel.memory.MergeSide
import com.mengpaw.kernel.memory.MergeWriteKind
import com.mengpaw.kernel.memory.SnapshotStore
import com.mengpaw.kernel.memory.TreeMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 同步协议 v0.3 驱动 (P2, docs/memory-git-sync-design.md §4.2): **纯 pull**。
 *
 * ```
 * HELLO{本机 heads} → same? 结束
 *                    → TREE_WALK × N (只取缺的快照/tree)
 *                    → OBJ_GET × 1   (只取缺的 blob)
 *                    → TreeMerge 合并 → 落盘 → 提交或快进采纳对端头
 * ```
 *
 * `ws` 与 `dev` 两个命名空间走**完全相同**的消息, 差异全部在 [SyncSpace]。
 * 收敛规则: 合并后内容与对端树一致 → 直接采纳对端 heads (`setHeads` + `setLocalRef`),
 * 不再造新快照 — 否则双方每轮都会生成新的合并提交互相追, heads 永远不同。
 */
internal class TwinSyncV3(
    private val serverSupplier: () -> AcpServer?,
    private val transportSupplier: () -> AcpTransport?,
    private val peers: ConcurrentHashMap<String, TwinPeerInfo>,
    private val syncState: MutableStateFlow<TwinSyncState>,
    private val deviceId: String,
    private val agentName: String,
    private val deviceName: String
) {

    companion object {
        const val PROTOCOL_VERSION: String = SyncV3Messages.PROTOCOL_VERSION
        private const val TIMEOUT_MS = 20_000L
        private const val MAX_TREE_WALKS = 16

        /** 双栈判定 (§4.4): 卡片 `protocolVersion >= 0.3` 走 v0.3; 缺卡片/解析失败 → v0.2。 */
        fun supportsV3(cardJson: String?): Boolean =
            versionAtLeast(protocolVersionOf(cardJson), PROTOCOL_VERSION)

        /** 从能力卡提取协议版本; 旧卡片 (无字段) 视为 0.2。 */
        fun protocolVersionOf(cardJson: String?): String {
            if (cardJson.isNullOrBlank()) return "0.2"
            return try {
                val obj = Json.parseToJsonElement(cardJson) as? JsonObject ?: return "0.2"
                (obj["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: "0.2"
            } catch (_: Exception) {
                "0.2"
            }
        }

        /** 语义化版本比较 (逐段数值); 非法段按 0 处理。 */
        fun versionAtLeast(version: String, min: String): Boolean {
            val have = version.trim().split('.')
            val need = min.trim().split('.')
            for (index in 0 until maxOf(have.size, need.size)) {
                val a = have.getOrNull(index)?.toIntOrNull() ?: 0
                val b = need.getOrNull(index)?.toIntOrNull() ?: 0
                if (a != b) return a > b
            }
            return true
        }
    }

    private data class SpaceResult(val received: Int = 0, val conflicts: Int = 0, val error: String? = null)
    private data class Theirs(val files: Map<String, String>, val lamport: Long, val device: String)

    // ── 对外入口 ───────────────────────────────────────────────────

    suspend fun syncWithPeer(peerId: String): TwinSyncResult {
        val server = serverSupplier()
            ?: return TwinSyncResult(0, 0, 0, "ACP 服务未启动", "请先执行 self.acp start")
        val transport = transportSupplier()
            ?: return TwinSyncResult(0, 0, 0, "ACP 传输层未初始化", null)
        val peer = peers[peerId]
            ?: return TwinSyncResult(0, 0, 0, "节点未发现",
                "请确认对端设备在同一网络且已启动孪生服务。也可用 twin.peer.add <ip> 手动添加。")

        server.registerPeer(PeerAgent(
            agentId = peer.peerId, agentName = peer.agentName,
            address = peer.address, port = peer.port,
            capabilities = listOf("memory-twin/0.3")
        ))

        var received = 0
        var conflicts = 0
        val errors = mutableListOf<String>()
        for (space in spaces()) {
            val result = try {
                syncSpace(peer, space, transport)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorCollector.report(e, "TwinSyncV3.sync(${space.namespace})")
                SpaceResult(error = "同步异常: ${e.message ?: e::class.simpleName}")
            }
            received += result.received
            conflicts += result.conflicts
            result.error?.let { errors.add("${space.namespace}: $it") }
        }

        peers[peerId]?.let {
            it.lastSyncAt = System.currentTimeMillis()
            it.online = true
        }
        syncState.value = syncState.value.copy(
            lastFilesReceived = received,
            lastConflicts = conflicts,
            lastSyncAt = System.currentTimeMillis()
        )
        return TwinSyncResult(
            filesReceived = received,
            filesSent = 0,
            conflicts = conflicts,
            error = errors.firstOrNull(),
            suggestion = if (errors.isEmpty()) null else "可重试; 持续失败请检查对端 ACP 状态与网络连通性"
        )
    }

    /**
     * 命名空间清单: `ws-<agent>` + `dev` + `ledger-<agent>`。
     * 账本必须无条件参与 — 新设备即使自己没写过记忆, 也要把对端账本拉下来
     * (否则账本是"半份", 归纳/索引会漏历史)。
     */
    private fun spaces(): List<SyncSpace> = listOf(
        WorkspaceSpace(agentName),
        DevicesSpace(),
        LedgerSpace(agentName)
    )

    // ── 单命名空间时序 ─────────────────────────────────────────────

    private suspend fun syncSpace(peer: TwinPeerInfo, space: SyncSpace, transport: AcpTransport): SpaceResult {
        val ns = space.namespace
        val localHeads = SnapshotStore.heads(ns)
        val hello = AcpMessage.syncHello(deviceId, peer.peerId, ns, deviceId, localHeads, PROTOCOL_VERSION)
        val ackResponse = transport.sendForResult(hello, peer.peerId, TIMEOUT_MS)
            ?: return SpaceResult(error = "同步超时 (${TIMEOUT_MS / 1000}s)")
        if (!ackResponse.success) {
            return SpaceResult(error = ackResponse.message.ifBlank { "对端拒绝同步" })
        }
        val ack = SyncV3Messages.parseAck(ackResponse.data)
            ?: return SpaceResult(error = "ACK 解析失败 — 对端可能不是 v0.3")
        if (ack.same) return SpaceResult()
        val theirHeads = ack.heads.distinct()
        if (theirHeads.isEmpty()) return SpaceResult()   // 对端无快照: 无可拉取

        val theirs = fetchTheirs(peer, space, theirHeads, ack, transport)
            ?: return SpaceResult(error = "对端树或对象缺失, 本轮跳过")
        val theirFiles = readTheirs(theirs.files)
            ?: return SpaceResult(error = "本地缺少对端 blob, 无法合并")

        val oursFiles = space.scan()
        // 内容已一致 → 快进采纳对端头 (0 新增对象, 也是双方收敛的关键)
        if (space.snapshot() == theirs.files) {
            convergeHeads(space.namespace, theirHeads)
            return SpaceResult()
        }

        val baseFiles = baseFiles(ns)
        val oursSide = MergeSide(deviceId, headLamport(ns), deviceName)
        val theirsSide = MergeSide(theirs.device.ifBlank { peer.peerId }, theirs.lamport, peer.agentName)
        val outcome = TreeMerge.merge(
            base = baseFiles,
            ours = oursFiles,
            theirs = theirFiles,
            oursSide = oursSide,
            theirsSide = theirsSide,
            classOf = space::classOf
        )
        val applied = applyOutcome(space, outcome, peer.agentName)
        val mergedIds = space.snapshot()

        if (mergedIds == theirs.files) {
            convergeHeads(ns, theirHeads)
        } else {
            commitMerge(ns, mergedIds, localHeads + theirHeads, peer.agentName)
        }
        return applied
    }

    // ── 对象拉取 ───────────────────────────────────────────────────

    private suspend fun fetchTheirs(
        peer: TwinPeerInfo,
        space: SyncSpace,
        theirHeads: List<String>,
        ack: SyncV3Messages.SyncAck,
        transport: AcpTransport
    ): Theirs? {
        val ns = space.namespace
        // 只请求本地确实没有的 tree/快照
        val neededTrees = ack.missingTrees.filter { sha ->
            SnapshotStore.snapshot(ns, sha) == null && SnapshotStore.readTree(sha) == null
        }
        val fetched = mutableListOf<SyncV3Messages.TreeWalkResponse>()
        for (sha in neededTrees.take(MAX_TREE_WALKS)) {
            fetched.add(fetchTree(peer, ns, sha, transport) ?: return null)
        }

        val candidates = mutableListOf<Triple<Long, String, Map<String, String>>>()
        theirHeads.forEach { head ->
            val snapshot = SnapshotStore.snapshot(ns, head) ?: return@forEach
            val tree = SnapshotStore.readTree(snapshot.tree) ?: return@forEach
            candidates.add(Triple(snapshot.lamport, snapshot.device, tree))
        }
        fetched.forEach {
            candidates.add(Triple(it.snapshot?.lamport ?: 0L, it.snapshot?.device ?: "", it.files))
        }
        // 兜底: tree 已在本地但快照缺失 (上次中断) → 补拉快照头, 否则拿不到 lamport/parents
        if (candidates.isEmpty()) {
            for (head in theirHeads.take(MAX_TREE_WALKS)) {
                if (SnapshotStore.snapshot(ns, head) != null) continue
                val response = fetchTree(peer, ns, head, transport) ?: return null
                candidates.add(
                    Triple(
                        response.snapshot?.lamport ?: 0L,
                        response.snapshot?.device ?: "",
                        response.files
                    )
                )
            }
        }
        if (candidates.isEmpty()) return null
        val best = candidates.maxWithOrNull(compareBy({ it.first }, { it.second })) ?: return null

        // 批量取缺失 blob: 只请求本地没有的 (ack.missingBlobs 也可能包含已存在对象)
        val needed = LinkedHashSet<String>()
        candidates.forEach { candidate ->
            candidate.third.values.forEach { sha ->
                if (SnapshotStore.readBlob(sha) == null) needed.add(sha)
            }
        }
        ack.missingBlobs.forEach { sha -> if (SnapshotStore.readBlob(sha) == null) needed.add(sha) }
        if (needed.isNotEmpty()) {
            val response = transport.sendForResult(
                AcpMessage.objGet(deviceId, peer.peerId, ns, needed.toList()), peer.peerId, TIMEOUT_MS
            ) ?: return null
            if (!response.success) return null
            SyncV3Messages.parseObjGetResponse(response.data).forEach { (_, bytes) ->
                SnapshotStore.writeBlob(bytes)
            }
        }
        if (needed.any { SnapshotStore.readBlob(it) == null }) return null
        return Theirs(best.third, best.first, best.second)
    }

    /** 取一个 tree/快照: 快照原始字节原样入库 (本地历史/GC 才能回溯父快照), tree 按内容寻址重写。 */
    private suspend fun fetchTree(
        peer: TwinPeerInfo,
        ns: String,
        sha: String,
        transport: AcpTransport
    ): SyncV3Messages.TreeWalkResponse? {
        val response = transport.sendForResult(
            AcpMessage.treeWalk(deviceId, peer.peerId, ns, sha), peer.peerId, TIMEOUT_MS
        ) ?: return null
        if (!response.success) return null
        val parsed = SyncV3Messages.parseTreeWalkResponse(response.data) ?: return null
        parsed.snapshotRaw?.let { SnapshotStore.importObject(it) }
        SnapshotStore.writeTree(parsed.files)
        return parsed
    }

    private fun readTheirs(files: Map<String, String>): Map<String, ByteArray>? {
        val out = LinkedHashMap<String, ByteArray>()
        for ((path, sha) in files) {
            val bytes = SnapshotStore.readBlob(sha) ?: return null
            out[path] = bytes
        }
        return out
    }

    // ── 合并 base / 元数据 ─────────────────────────────────────────

    /** base = local ref 对应快照的 tree (§4.5 — 绝不回退成"最近提交")。 */
    private fun baseFiles(ns: String): Map<String, ByteArray> {
        val local = SnapshotStore.localRef(ns) ?: return emptyMap()
        val snapshot = SnapshotStore.snapshot(ns, local) ?: return emptyMap()
        val tree = SnapshotStore.readTree(snapshot.tree) ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        tree.forEach { (path, sha) ->
            SnapshotStore.readBlob(sha)?.let { out[path] = it }
        }
        return out
    }

    private fun headLamport(ns: String): Long =
        SnapshotStore.heads(ns).mapNotNull { SnapshotStore.snapshot(ns, it)?.lamport }.maxOrNull() ?: 0L

    // ── 落盘 ───────────────────────────────────────────────────────

    private fun applyOutcome(space: SyncSpace, outcome: MergeOutcome, fromDevice: String): SpaceResult {
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
        return SpaceResult(received, outcome.conflicts.size)
    }

    private fun commitMerge(ns: String, files: Map<String, String>, parents: List<String>, peerName: String) {
        val id = SnapshotStore.commit(
            namespace = ns,
            files = files,
            message = "合并 $peerName",
            agent = agentName,
            device = deviceId,
            parents = parents
        )
        SnapshotStore.setLocalRef(ns, id)
    }

    /**
     * 收敛 heads (内容已一致时) — 取**并集**, 不直接采纳对端 heads。
     *
     * 为什么不是单纯采纳: 本地 head 可能不在对端历史里 (双方各自提交后首轮相遇),
     * 直接丢弃会让本地提交变成不可达对象, GC 一跑历史就没了 (内容虽同, 审计链断)。
     * 并集是幂等的: 双方各保留对方 head 后, 下一轮 HELLO 的 heads 集合相等,
     * 自然收敛且不产生新快照 —— 这与"避免互造合并提交"的目标一致。
     */
    private fun convergeHeads(ns: String, theirHeads: List<String>) {
        val union = (SnapshotStore.heads(ns) + theirHeads).distinct()
        SnapshotStore.setHeads(ns, union)
        val latest = union.mapNotNull { SnapshotStore.snapshot(ns, it) }
            .maxByOrNull { it.lamport }?.id ?: union.lastOrNull()
        if (latest != null) SnapshotStore.setLocalRef(ns, latest)
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

