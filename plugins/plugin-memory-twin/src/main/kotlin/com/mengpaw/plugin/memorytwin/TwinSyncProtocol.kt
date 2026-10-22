// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.acp.PeerAgent
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.ports.Ports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 孪生同步协议 — 从 TwinSyncEngine 拆分 (职责: 清单交换 + 差异拉取 + 能力广播)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式): 服务/传输供给、对端表、
 * 可观测状态流、QoS 读取。行为与拆分前完全一致。
 */
internal class TwinSyncProtocol(
    private val serverSupplier: () -> AcpServer?,
    private val transportSupplier: () -> AcpTransport?,
    private val peers: java.util.concurrent.ConcurrentHashMap<String, TwinPeerInfo>,
    private val syncState: MutableStateFlow<TwinSyncState>,
    private val deviceId: String,
    private val agentName: String,
    private val qosLevel: () -> TwinSyncEngine.QosLevel
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Execute a full sync cycle with a specific peer (v0.22.0 工作区文件同步):
     * 1. WS_MANIFEST: 发本机清单, 响应 = 对端给我们的文件 (send) + 对端缺的文件列表 (request)
     * 2. WS_PULL: 请求我们缺的文件内容
     * 3. TwinWorkspace LWW 落盘 (冲突 → .conflict 备份)
     */
    suspend fun syncWithPeer(peerId: String): TwinSyncResult {
        val server = serverSupplier()
        if (server == null) return TwinSyncResult(0, 0, 0, "ACP 服务未启动", "请先执行 self.acp start")
        val transport = transportSupplier()
        if (transport == null) return TwinSyncResult(0, 0, 0, "ACP 传输层未初始化", null)

        return try {
            val peer = peers[peerId]
            if (peer == null) return TwinSyncResult(0, 0, 0, "节点未发现",
                "请确认对端设备在同一网络且已启动孪生服务。也可用 twin.peer.add <ip> 手动添加。")

            // Register peer in ACP server so transport can reach it
            server.registerPeer(PeerAgent(
                agentId = peerId, agentName = peer.agentName,
                address = peer.address, port = peer.port,
                capabilities = listOf("memory-twin/0.1")
            ))

            // Step 1: 交换清单 — 请求-响应一轮完成 (响应体解析修复见 AcpTransport.sendForResult)
            val manifestMsg = AcpMessage.wsManifest(deviceId, peerId, buildManifestPayload(agentName))
            val manifestResp = transport.sendForResult(manifestMsg, peerId, 20_000)
            if (manifestResp == null) {
                return TwinSyncResult(0, 0, 0, "同步超时 (20s)",
                    "对端未在规定时间内响应。检查: 1) 对端是否在线 2) ACP 端口 ${Ports.ACP} 是否互通 3) 防火墙是否拦截")
            }
            if (!manifestResp.success) {
                return TwinSyncResult(0, 0, 0, manifestResp.message, null)
            }

            // P0 fix: 收发两端共用同一套载荷定义 (buildManifestPayload / parseManifestResponse),
            // 形态漂移会在这里直接表现为解析失败, 而不是静默丢文件。
            val manifest = try { parseManifestResponse(manifestResp.data) } catch (e: Exception) {
                ErrorCollector.report(e, "TwinSyncEngine.wsManifest.parse")
                return TwinSyncResult(0, 0, 0, "清单响应解析失败: ${e.message}", null)
            }

            // 接收对端文件 (对端 → 本机)
            var received = 0; var conflicts = 0; var writeErrors = 0
            manifest.send.forEach { file ->
                when (TwinWorkspace.applyWorkspaceFile(agentName, file.relPath, file.content, peer.agentName, file.mtime)) {
                    "applied" -> received++
                    "conflict" -> conflicts++
                    "error" -> writeErrors++
                    // "same" / "rejected": 未落盘, 不计入统计
                }
            }

            // Step 2: 拉取我们缺的文件 (本机 → 对端)
            val requestPaths = manifest.request
            if (requestPaths.isNotEmpty()) {
                val pullMsg = AcpMessage.wsPull(deviceId, peerId, buildJsonObject {
                    put("paths", buildJsonArray { requestPaths.forEach { add(JsonPrimitive(it)) } })
                }.toString())
                val pullResp = transport.sendForResult(pullMsg, peerId, 20_000)
                if (pullResp != null && pullResp.success) {
                    try {
                        parsePullResponse(pullResp.data).forEach { (relPath, content) ->
                            when (TwinWorkspace.applyWorkspaceFile(agentName, relPath, content,
                                peer.agentName, System.currentTimeMillis())) {
                                "applied" -> received++
                                "conflict" -> conflicts++
                                "error" -> writeErrors++
                            }
                        }
                    } catch (e: Exception) {
                        ErrorCollector.report(e, "TwinSyncEngine.wsPull.parse")
                    }
                }
            }

            // 更新对端状态
            peers[peerId]?.let {
                it.lastSyncAt = System.currentTimeMillis()
                it.online = true
            }
            syncState.value = syncState.value.copy(
                lastFilesReceived = received,
                lastConflicts = conflicts,
                lastSyncAt = System.currentTimeMillis()
            )
            // P1: 写入失败不再被静默吞掉 — 回传可读错误, 但不中断其余文件
            // P1 修正: filesSent 应为**实际发出的文件数** (send 载荷条目), 旧实现填的是"我们待拉取的路径数"
            TwinSyncResult(received, manifest.send.size, conflicts,
                if (writeErrors > 0) "有 $writeErrors 个文件写入失败" else null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.syncWithPeer($peerId)")
            TwinSyncResult(0, 0, 0, "同步异常: ${e.message}", "请重试。若持续失败，检查对端 ACP 状态和网络连通性。")
        }
    }

    /**
     * Sync with all known online peers.
     *
     * @param syncOne 单节点同步实现 (引擎注入 → 逐节点做 v0.2/v0.3 双栈判定);
     *                缺省走本对象的 v0.2 实现, 保持旧调用方行为不变。
     */
    suspend fun syncWithAllPeers(
        syncOne: suspend (String) -> TwinSyncResult = ::syncWithPeer
    ): List<TwinSyncResult> {
        if (qosLevel() == TwinSyncEngine.QosLevel.METERED) {
            return listOf(TwinSyncResult(0, 0, 0, "按流量计费模式下已暂停自动同步", "使用 twin.sync 手动触发"))
        }
        val online = peers.values.filter { it.online }
        syncState.value = syncState.value.copy(
            phase = SyncPhase.SYNCING, totalPeers = online.size, completedPeers = 0
        )
        val results = mutableListOf<TwinSyncResult>()
        online.forEach { peer ->
            val result = syncOne(peer.peerId)
            results.add(result)
            if (result.filesReceived > 0 || result.conflicts > 0) {
                syncState.value = syncState.value.copy(
                    completedPeers = syncState.value.completedPeers + 1
                )
            }
        }
        syncState.value = syncState.value.copy(
            phase = SyncPhase.IDLE,
            completedPeers = syncState.value.completedPeers,
            lastSyncAt = System.currentTimeMillis()
        )
        return results
    }

    /** Announce our capability card to a peer. */
    suspend fun announceCapability(peerId: String, card: String): Boolean {
        val transport = transportSupplier() ?: return false
        return try {
            val msg = AcpMessage.capabilityAnnounce(deviceId, peerId, card)
            transport.send(msg)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.announceCapability")
            false
        }
    }

    /** Announce capability to all known peers. */
    suspend fun broadcastCapability(card: String) {
        val transport = transportSupplier() ?: return
        peers.keys.forEach { peerId ->
            try {
                val msg = AcpMessage.capabilityAnnounce(deviceId, peerId, card)
                transport.send(msg)
            } catch (_: Exception) { /* best effort */ }
        }
    }
}

// ── v0.2 线协议载荷: 发送端与接收端共用的唯一事实源 ─────────────────────
// P0 回归: 此前发送端写扁平字符串、接收端按对象解析, 只要真有差异就抛异常。
// 两端都必须走下面这三个函数, 任何一端单独改形态都会被 TwinWireContractTest 抓住。

/** 对端在清单响应里给我们的一个文件。 */
internal data class ManifestFile(val relPath: String, val content: String, val mtime: Long)

/** 清单响应: 对端给我们的文件 + 对端缺的路径。 */
internal data class ManifestResponse(val send: List<ManifestFile>, val request: List<String>)

private val wireJson = Json { ignoreUnknownKeys = true }

/** 构造 WS_MANIFEST 载荷 (发送端唯一入口)。 */
internal fun buildManifestPayload(agentName: String): String = buildJsonObject {
    put("files", buildJsonObject {
        TwinWorkspace.buildManifest(agentName).forEach { (relPath, entry) ->
            put(relPath, buildJsonObject {
                put("hash", JsonPrimitive(TwinWorkspace.manifestHash(entry.hash)))
                put("mtime", JsonPrimitive(entry.mtime))
            })
        }
    })
}.toString()

/**
 * 解析 WS_MANIFEST 响应。路径逐条消毒, 坏条目跳过而非整包失败
 * (但 `content` 缺失视为协议不兼容, 该条跳过并计数, 由调用方按 0 文件处理)。
 */
internal fun parseManifestResponse(data: String): ManifestResponse {
    val obj = wireJson.parseToJsonElement(data).jsonObject
    val send = mutableListOf<ManifestFile>()
    obj["send"]?.jsonObject?.forEach { (relPath, value) ->
        val safe = TwinWorkspace.sanitizeRelPath(relPath) ?: return@forEach
        val fileObj = value as? JsonObject ?: return@forEach
        val content = fileObj["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        val mtime = fileObj["mtime"]?.jsonPrimitive?.longOrNull ?: 0L
        send.add(ManifestFile(safe, content, mtime))
    }
    val request = obj["request"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.mapNotNull { TwinWorkspace.sanitizeRelPath(it) }
        ?: emptyList()
    return ManifestResponse(send, request)
}

/** 解析 WS_PULL 响应: 路径逐条消毒, 非字符串内容跳过。 */
internal fun parsePullResponse(data: String): Map<String, String> {
    val files = wireJson.parseToJsonElement(data).jsonObject["files"]?.jsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    files.forEach { (relPath, value) ->
        val safe = TwinWorkspace.sanitizeRelPath(relPath) ?: return@forEach
        val content = value.jsonPrimitive.contentOrNull ?: return@forEach
        out[safe] = content
    }
    return out
}
