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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
            val myManifest = TwinWorkspace.buildManifest(agentName)
            val manifestMsg = AcpMessage.wsManifest(deviceId, peerId, buildJsonObject {
                put("files", buildJsonObject {
                    myManifest.forEach { (relPath, entry) ->
                        put(relPath, buildJsonObject {
                            put("hash", JsonPrimitive(entry.hash.take(16)))
                            put("mtime", JsonPrimitive(entry.mtime))
                        })
                    }
                })
            }.toString())
            val manifestResp = transport.sendForResult(manifestMsg, peerId, 20_000)
            if (manifestResp == null) {
                return TwinSyncResult(0, 0, 0, "同步超时 (20s)",
                    "对端未在规定时间内响应。检查: 1) 对端是否在线 2) ACP 端口 ${Ports.ACP} 是否互通 3) 防火墙是否拦截")
            }
            if (!manifestResp.success) {
                return TwinSyncResult(0, 0, 0, manifestResp.message, null)
            }

            val respObj = try { json.parseToJsonElement(manifestResp.data).jsonObject } catch (e: Exception) {
                return TwinSyncResult(0, 0, 0, "清单响应解析失败: ${e.message}", null)
            }

            // 接收对端文件 (对端 → 本机)
            var received = 0; var conflicts = 0
            respObj["send"]?.jsonObject?.forEach { (relPath, value) ->
                val fileObj = value.jsonObject
                val content = fileObj["content"]?.jsonPrimitive?.content ?: ""
                val mtime = fileObj["mtime"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                val r = TwinWorkspace.applyWorkspaceFile(agentName, relPath, content, peer.agentName, mtime)
                when (r) {
                    "applied" -> received++
                    "conflict" -> conflicts++
                }
            }

            // Step 2: 拉取我们缺的文件 (本机 → 对端)
            val requestPaths = respObj["request"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList()
            if (requestPaths.isNotEmpty()) {
                val pullMsg = AcpMessage.wsPull(deviceId, peerId, buildJsonObject {
                    put("paths", buildJsonArray { requestPaths.forEach { add(JsonPrimitive(it)) } })
                }.toString())
                val pullResp = transport.sendForResult(pullMsg, peerId, 20_000)
                if (pullResp != null && pullResp.success) {
                    try {
                        val filesObj = json.parseToJsonElement(pullResp.data).jsonObject["files"]?.jsonObject
                        filesObj?.forEach { (relPath, content) ->
                            val r = TwinWorkspace.applyWorkspaceFile(agentName, relPath, content.jsonPrimitive.content,
                                peer.agentName, System.currentTimeMillis())
                            when (r) {
                                "applied" -> received++
                                "conflict" -> conflicts++
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
            TwinSyncResult(received, requestPaths.size, conflicts, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.syncWithPeer($peerId)")
            TwinSyncResult(0, 0, 0, "同步异常: ${e.message}", "请重试。若持续失败，检查对端 ACP 状态和网络连通性。")
        }
    }

    /** Sync with all known online peers. */
    suspend fun syncWithAllPeers(): List<TwinSyncResult> {
        if (qosLevel() == TwinSyncEngine.QosLevel.METERED) {
            return listOf(TwinSyncResult(0, 0, 0, "按流量计费模式下已暂停自动同步", "使用 twin.sync 手动触发"))
        }
        val online = peers.values.filter { it.online }
        syncState.value = syncState.value.copy(
            phase = SyncPhase.SYNCING, totalPeers = online.size, completedPeers = 0
        )
        val results = mutableListOf<TwinSyncResult>()
        online.forEach { peer ->
            val result = syncWithPeer(peer.peerId)
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
