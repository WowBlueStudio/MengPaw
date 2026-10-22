// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * 同步协议 v0.3 的载荷 (docs/memory-git-sync-design.md §4.1) — 纯数据编解码。
 *
 * | 消息 | 载荷 |
 * |---|---|
 * | `SYNC_HELLO` | `{proto, deviceId, ns, heads:[sha]}` |
 * | `SYNC_ACK` | `{same, heads:[sha], missingTrees:[sha], missingBlobs:[sha]}` |
 * | `TREE_WALK` | `{ns, sha}` → `{snapshot?, files:{relPath: blobSha}, snapshotRaw?}` |
 * | `OBJ_GET` | `{ns, blobs:[sha]}` → `{blobs:{sha: base64}}` |
 *
 * 与设计的两处**实现约定** (P2 报告"与设计不一致"处已登记):
 * 1. `TREE_WALK` 的 `sha` 取**快照头 id** (也可传 tree id), 响应附带快照元数据与原始快照字节 —
 *    三方合并需要 (lamport, deviceId) 排序, 本地历史也需要父快照对象才能回溯;
 * 2. `OBJ_GET` 的 blob 内容为 base64(原始字节), 不再二次 zlib — HTTP 层已有 AES 加密,
 *    对象本身落盘时才是 zlib。
 */
object SyncV3Messages {

    /** 协议版本 (本机宣告值)。 */
    const val PROTOCOL_VERSION = "0.3"

    /** 单次 OBJ_GET 的 blob 上限 — 防对端用超长列表拖死解析。 */
    const val MAX_BLOBS_PER_GET = 2048

    /** 命名空间: 设备库存 (§4.2 — 与 ws 走完全相同的消息)。 */
    const val NS_DEVICES = "dev"

    /** 工作区命名空间 (`ws-<agentName>`)。 */
    fun workspaceNamespace(agentName: String): String = "ws-$agentName"

    /** 记忆账本命名空间 (`ledger-<agentName>`, 根 `{AGENTS}/twin/ledger/<agent>/`; 设计 §6.1)。 */
    fun ledgerNamespace(agentName: String): String = "ledger-$agentName"

    /** 命名空间是否本机可服务 (只认 `ws-<本机 agent>` / `dev` / `ledger-<本机 agent>`, 不暴露任意前缀)。 */
    fun isAllowedNamespace(ns: String, agentName: String): Boolean =
        ns == NS_DEVICES || ns == workspaceNamespace(agentName) || ns == ledgerNamespace(agentName)

    private val json = Json { ignoreUnknownKeys = true }
    private val HEX64 = Regex("[0-9a-f]{64}")

    /** 对象 id 形态校验。 */
    fun isObjectId(value: String): Boolean = HEX64.matches(value)

    // ── SYNC_HELLO ─────────────────────────────────────────────────

    data class SyncHello(
        val proto: String,
        val deviceId: String,
        val ns: String,
        val heads: List<String>
    )

    fun helloPayload(proto: String, deviceId: String, ns: String, heads: List<String>): String =
        buildJsonObject {
            put("proto", proto)
            put("deviceId", deviceId)
            put("ns", ns)
            put("heads", ids(heads))
        }.toString()

    fun parseHello(text: String): SyncHello? {
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            val proto = obj.string("proto") ?: return null
            val deviceId = obj.string("deviceId") ?: return null
            val ns = obj.string("ns") ?: return null
            SyncHello(proto, deviceId, ns, obj.ids("heads"))
        } catch (_: Exception) {
            null
        }
    }

    // ── SYNC_ACK ───────────────────────────────────────────────────

    data class SyncAck(
        val same: Boolean,
        val heads: List<String>,
        val missingTrees: List<String>,
        val missingBlobs: List<String>
    )

    fun ackPayload(same: Boolean, heads: List<String>, missingTrees: List<String>, missingBlobs: List<String>): String =
        buildJsonObject {
            put("same", same)
            put("heads", ids(heads))
            put("missingTrees", ids(missingTrees))
            put("missingBlobs", ids(missingBlobs))
        }.toString()

    fun parseAck(text: String): SyncAck? {
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            SyncAck(
                same = obj["same"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() } ?: false,
                heads = obj.ids("heads"),
                missingTrees = obj.ids("missingTrees"),
                missingBlobs = obj.ids("missingBlobs")
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── TREE_WALK ──────────────────────────────────────────────────

    data class TreeWalkRequest(val ns: String, val sha: String)

    /** 快照元数据 (与 [com.mengpaw.kernel.memory.Snapshot] 同构, 但可跨进程序列化)。 */
    data class SnapshotMeta(
        val id: String,
        val tree: String,
        val parents: List<String>,
        val lamport: Long,
        val wall: Long,
        val device: String,
        val author: String,
        val message: String
    )

    data class TreeWalkResponse(
        val snapshot: SnapshotMeta?,
        val files: Map<String, String>,
        /** 快照对象原始字节 — 请求方 import 后本地历史才能回溯父快照。 */
        val snapshotRaw: ByteArray?
    )

    fun treeWalkPayload(ns: String, sha: String): String = buildJsonObject {
        put("ns", ns)
        put("sha", sha)
    }.toString()

    fun parseTreeWalk(text: String): TreeWalkRequest? {
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            val ns = obj.string("ns") ?: return null
            val sha = obj.string("sha") ?: return null
            TreeWalkRequest(ns, sha)
        } catch (_: Exception) {
            null
        }
    }

    fun treeWalkResponsePayload(
        snapshot: SnapshotMeta?,
        treeId: String,
        files: Map<String, String>,
        snapshotRaw: ByteArray?
    ): String = buildJsonObject {
        if (snapshot != null) {
            put("snapshot", buildJsonObject {
                put("id", snapshot.id)
                put("tree", snapshot.tree)
                put("parents", ids(snapshot.parents))
                put("lamport", snapshot.lamport)
                put("wall", snapshot.wall)
                put("device", snapshot.device)
                put("author", snapshot.author)
                put("msg", snapshot.message)
            })
        }
        put("tree", treeId)
        put("files", buildJsonObject { files.toSortedMap().forEach { (k, v) -> put(k, v) } })
        if (snapshotRaw != null) put("snapshotRaw", Base64.getEncoder().encodeToString(snapshotRaw))
    }.toString()

    fun parseTreeWalkResponse(text: String): TreeWalkResponse? {
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            val metaObj = obj["snapshot"] as? JsonObject
            val meta = metaObj?.let {
                val id = it.string("id") ?: return null
                val tree = it.string("tree") ?: return null
                SnapshotMeta(
                    id = id,
                    tree = tree,
                    parents = it.ids("parents"),
                    lamport = (it["lamport"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    wall = (it["wall"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    device = it.string("device") ?: "",
                    author = it.string("author") ?: "",
                    message = it.string("msg") ?: ""
                )
            }
            val filesObj = obj["files"] as? JsonObject ?: return null
            val files = LinkedHashMap<String, String>()
            filesObj.forEach { (path, value) ->
                val sha = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                if (isObjectId(sha)) files[path] = sha
            }
            val raw = (obj["snapshotRaw"] as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            TreeWalkResponse(meta, files, raw)
        } catch (_: Exception) {
            null
        }
    }

    // ── OBJ_GET ────────────────────────────────────────────────────

    data class ObjGetRequest(val ns: String, val blobs: List<String>)

    fun objGetPayload(ns: String, blobs: List<String>): String = buildJsonObject {
        put("ns", ns)
        put("blobs", ids(blobs))
    }.toString()

    fun parseObjGet(text: String): ObjGetRequest? {
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            val ns = obj.string("ns") ?: return null
            ObjGetRequest(ns, obj.ids("blobs"))
        } catch (_: Exception) {
            null
        }
    }

    /** blob 内容 → Response payload (base64)。 */
    fun objGetResponsePayload(blobs: Map<String, ByteArray>): String = buildJsonObject {
        put("blobs", buildJsonObject {
            blobs.toSortedMap().forEach { (id, bytes) ->
                put(id, Base64.getEncoder().encodeToString(bytes))
            }
        })
    }.toString()

    fun parseObjGetResponse(text: String): Map<String, ByteArray> {
        val obj = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: return emptyMap()
        val blobsObj = obj["blobs"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        blobsObj.forEach { (id, value) ->
            if (!isObjectId(id)) return@forEach
            val encoded = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: Exception) { return@forEach }
            out[id] = bytes
        }
        return out
    }

    // ── JSON 助手 ──────────────────────────────────────────────────

    private fun ids(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { if (isObjectId(it)) add(JsonPrimitive(it)) }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.ids(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { isObjectId(it) }
            ?: emptyList()
}
