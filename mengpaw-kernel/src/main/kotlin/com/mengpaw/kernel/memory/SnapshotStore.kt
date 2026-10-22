// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException

/**
 * 内容寻址对象库 (P1, docs/memory-git-sync-design.md §3)。
 *
 * ## 对象格式 (§3.2)
 * - blob: `zlib("blob <len>\0" + content)`, SHA-256 覆盖 header + content
 * - tree: `zlib(JSON {relPath: blobSha})` — v1 扁平结构, 不做层级 tree
 * - snapshot: `zlib(JSON {tree, parents[], author{deviceId,agent}, lamport, wall, msg})`
 *
 * ## 存储布局 (§3.1)
 * ```
 * {AGENTS}/twin/store/
 *   objects/ab/cdef…     ← 前 2 位分桶
 *   refs/<ns>/heads      ← 一行一个 sha (运维可读)
 *   refs/<ns>/local      ← 本机上次提交/同步头 (= 合并 base)
 *   tmp/                 ← 原子写中转
 * ```
 *
 * ## 不变量
 * - **原子写**: 先写 tmp, 再 rename 覆盖; 失败回退为复制覆盖, 绝不先删目标。
 * - **幂等**: 内容寻址, 同 sha 已存在直接返回, 不重写。
 * - **损坏防御**: 解压/解析/校验失败 → 记 [ErrorCollector] + 当缺失处理, 不抛给调用方。
 */
object SnapshotStore {

    private const val BLOB_PREFIX = "blob "
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val REF_UNSAFE = Regex("[^\\p{L}\\p{N}_-]")

    // ── blob ────────────────────────────────────────────────────────

    /** 写入文件内容, 返回 SHA-256。同内容重复写入不落盘 (幂等)。 */
    fun writeBlob(bytes: ByteArray): String {
        val header = "$BLOB_PREFIX${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
        return ObjectStore.write(header + bytes)
    }

    /** 读取 blob 内容; 缺失/损坏/校验失败 → null (不抛)。 */
    fun readBlob(id: String): ByteArray? {
        val raw = ObjectStore.read(id) ?: return null
        return try {
            val nul = raw.indexOf(0.toByte())
            if (nul < 0) throw IOException("blob 头缺少 NUL 分隔符")
            val header = String(raw, 0, nul, Charsets.UTF_8)
            if (!header.startsWith(BLOB_PREFIX)) throw IOException("非 blob 对象: $header")
            val len = header.removePrefix(BLOB_PREFIX).toIntOrNull()
                ?: throw IOException("blob 长度非法: $header")
            val content = raw.copyOfRange(nul + 1, raw.size)
            if (content.size != len) throw IOException("blob 长度不符: 头 $len / 实 ${content.size}")
            content
        } catch (e: Exception) {
            ObjectStore.reportCorrupt("readBlob", id, e)
            null
        }
    }

    // ── tree ────────────────────────────────────────────────────────

    /** 写入扁平清单 (`relPath -> blobId`), 键排序保证同内容同 sha。 */
    fun writeTree(files: Map<String, String>): String {
        val sorted = files.toSortedMap()
        val obj = JsonObject(sorted.mapValues { JsonPrimitive(it.value) })
        return ObjectStore.write(obj.toString().toByteArray(Charsets.UTF_8))
    }

    /** 读取清单; 缺失/损坏 → null。 */
    fun readTree(id: String): Map<String, String>? {
        val obj = readJsonObject(id) ?: return null
        val result = LinkedHashMap<String, String>()
        for ((key, value) in obj) {
            val sha = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            result[key] = sha
        }
        return result
    }

    // ── snapshot / refs ─────────────────────────────────────────────

    /**
     * 提交一次快照。
     *
     * @param parents 空 = 以当前 heads 为父 (本地提交); 显式给出 = 合并/导入提交
     * @return 新快照 ID
     */
    @Synchronized
    fun commit(
        namespace: String,
        files: Map<String, String>,
        message: String,
        agent: String,
        device: String,
        parents: List<String> = emptyList()
    ): String {
        val tree = writeTree(files)
        val current = heads(namespace)
        val effectiveParents = if (parents.isEmpty()) {
            current
        } else {
            parents.filter { ObjectStore.isObjectId(it) }.distinct()
        }
        val lamport = nextLamport(namespace, effectiveParents)
        val payload = encodeSnapshot(
            tree = tree,
            parents = effectiveParents,
            lamport = lamport,
            wall = System.currentTimeMillis(),
            agent = agent,
            device = device,
            message = message
        )
        val id = ObjectStore.write(payload)

        // heads 前进: 被消费的父节点移出 head 集合, 新快照加入 (保留无关分叉 head)
        val newHeads = LinkedHashSet(current)
        newHeads.removeAll(effectiveParents.toSet())
        newHeads.add(id)
        writeRef(namespace, "heads", newHeads.toList())
        // 注意: **不要**在这里前进 local — local 的语义是"上次成功同步头" (= 三方合并 base)。
        // 本地提交若写了它, 下一次同步会拿一个对端没有的快照当 base, 合并会误判成
        // "对端删掉了我本地的改动"。local 只由同步层在真正对齐后通过 [setLocalRef] 写入。
        return id
    }

    /** 显式设置合并 base — 仅同步层使用 (双方对齐后写入)。 */
    @Synchronized
    fun setLocalRef(namespace: String, id: String) {
        if (!ObjectStore.isObjectId(id)) return
        writeRef(namespace, "local", listOf(id))
    }

    /**
     * 显式设置 heads — 仅同步层在**快进采纳对端头**时使用 (不产生新快照, 避免合并提交互刷)。
     * 非法 id 被过滤; 空列表 = 清空 heads。
     */
    @Synchronized
    fun setHeads(namespace: String, ids: List<String>) {
        writeRef(namespace, "heads", ids.filter { ObjectStore.isObjectId(it) }.distinct())
    }

    /** 读取对象原始字节 (P2 拉取对端快照/清单对象用); 缺失/损坏 → null。 */
    fun exportObject(id: String): ByteArray? = ObjectStore.read(id)

    /**
     * 写入对端对象原始字节 (内容寻址, 幂等) — 返回对象 id; 非 64 位十六进制 → null。
     * 仅同步层使用: 对端快照必须原样落库, 否则本地历史无法回溯父快照。
     */
    fun importObject(bytes: ByteArray): String? = try {
        ObjectStore.write(bytes).takeIf { ObjectStore.isObjectId(it) }
    } catch (e: Exception) {
        ErrorCollector.report(e, "SnapshotStore.importObject")
        null
    }

    /** 当前 heads (一行一个 sha; 缺失/损坏 → 空列表)。 */
    fun heads(namespace: String): List<String> = readRef(namespace, "heads")

    /** 本机上次**成功同步**头 (= 合并 base); 本地提交不前进, 无 → null。 */
    fun localRef(namespace: String): String? = readRef(namespace, "local").firstOrNull()

    /** 读取快照元数据; 缺失/损坏 → null。 */
    fun snapshot(namespace: String, id: String): Snapshot? {
        val raw = ObjectStore.read(id) ?: return null
        return parseSnapshot(id, raw)
    }

    /** 从 heads + local 出发沿 parents 回溯, 按 lamport/wall 倒序取最近 [limit] 个。 */
    fun history(namespace: String, limit: Int = 20): List<Snapshot> {
        if (limit <= 0) return emptyList()
        val seeds = LinkedHashSet<String>()
        seeds.addAll(heads(namespace))
        localRef(namespace)?.let { seeds.add(it) }
        if (seeds.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val all = mutableListOf<Snapshot>()
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val snap = snapshot(namespace, id) ?: continue
            all.add(snap)
            snap.parents.forEach { if (it !in seen) queue.add(it) }
        }
        return all.sortedWith(
            compareByDescending<Snapshot> { it.lamport }.thenByDescending { it.wall }
        ).take(limit)
    }

    /**
     * 垃圾回收 — 从 heads + local 做可达性标记, 清理不可达对象。
     *
     * 保留策略取 `keepDays` 与 `keepCount` **较宽**的一个 (并集): 未达龄就保留,
     * 或仍属最近 [keepCount] 个快照也保留。
     *
     * @return 删除的对象数
     */
    @Synchronized
    fun gc(namespace: String, keepDays: Int = 30, keepCount: Int = 200): Int {
        val all = ObjectStore.listIds()
        val keep = HashSet<String>()

        val seeds = LinkedHashSet<String>()
        seeds.addAll(heads(namespace))
        localRef(namespace)?.let { seeds.add(it) }
        seeds.forEach { markReachable(namespace, it, keep) }

        // 保留策略: 所有可解析的 snapshot 对象按 wall 倒序, 达龄或属于最近 keepCount 个
        val snapshots = all.mapNotNull { id -> ObjectStore.read(id)?.let { parseSnapshot(id, it) } }
        val cutoff = System.currentTimeMillis() - keepDays.coerceAtLeast(0) * DAY_MS
        snapshots.sortedByDescending { it.wall }.forEachIndexed { index, snap ->
            if (index < keepCount.coerceAtLeast(0) || snap.wall >= cutoff) {
                markReachable(namespace, snap.id, keep)
            }
        }

        var removed = 0
        for (id in all) {
            if (id in keep) continue
            if (ObjectStore.delete(id)) removed++
        }
        return removed
    }

    /** 对象库总体积 (字节)。 */
    fun storeSizeBytes(): Long = ObjectStore.sizeBytes()

    /** 对象库对象总数。 */
    fun objectCount(): Int = ObjectStore.listIds().size

    // ── 内部: 编码/解析 ─────────────────────────────────────────────

    private fun readJsonObject(id: String): JsonObject? {
        val raw = ObjectStore.read(id) ?: return null
        return try {
            Json.parseToJsonElement(String(raw, Charsets.UTF_8)) as? JsonObject
                ?: throw IOException("对象不是 JSON 对象")
        } catch (e: Exception) {
            ObjectStore.reportCorrupt("readJsonObject", id, e)
            null
        }
    }

    /** 宽解析: 非 snapshot 对象 (tree/blob) 返回 null 且不记错 — GC 枚举时用。 */
    private fun parseSnapshot(id: String, raw: ByteArray): Snapshot? {
        return try {
            val obj = Json.parseToJsonElement(String(raw, Charsets.UTF_8)) as? JsonObject
                ?: return null
            val tree = (obj["tree"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null
            val lamport = (obj["lamport"] as? JsonPrimitive)?.longOrNull ?: return null
            val wall = (obj["wall"] as? JsonPrimitive)?.longOrNull ?: return null
            val parents = (obj["parents"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()
            val authorObj = obj["author"] as? JsonObject
            val agent = (authorObj?.get("agent") as? JsonPrimitive)?.contentOrNull
                ?: (obj["author"] as? JsonPrimitive)?.contentOrNull
                ?: ""
            val device = (authorObj?.get("deviceId") as? JsonPrimitive)?.contentOrNull ?: ""
            val message = (obj["msg"] as? JsonPrimitive)?.contentOrNull ?: ""
            Snapshot(
                id = id,
                tree = tree,
                parents = parents,
                lamport = lamport,
                wall = wall,
                author = agent,
                message = message,
                device = device
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeSnapshot(
        tree: String,
        parents: List<String>,
        lamport: Long,
        wall: Long,
        agent: String,
        device: String,
        message: String
    ): ByteArray {
        val obj = buildJsonObject {
            put("tree", tree)
            putJsonArray("parents") { parents.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("author") {
                put("deviceId", device)
                put("agent", agent)
            }
            put("lamport", lamport)
            put("wall", wall)
            put("msg", message)
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    // ── 内部: 可达性 ────────────────────────────────────────────────

    private fun markReachable(namespace: String, id: String, keep: MutableSet<String>) {
        val stack = ArrayDeque<String>()
        val seen = HashSet<String>()
        stack.add(id)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!seen.add(current) || !ObjectStore.isObjectId(current)) continue
            keep.add(current)
            val snap = snapshot(namespace, current) ?: continue
            if (ObjectStore.isObjectId(snap.tree)) {
                keep.add(snap.tree)
                readTree(snap.tree)?.values?.forEach { blob ->
                    if (ObjectStore.isObjectId(blob)) keep.add(blob)
                }
            }
            snap.parents.forEach { if (it !in seen) stack.add(it) }
        }
    }

    // ── 内部: refs / 时钟 ───────────────────────────────────────────

    private fun readRef(namespace: String, name: String): List<String> {
        val file = refFile(namespace, name)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().map { it.trim() }
                .filter { ObjectStore.isObjectId(it) }.distinct()
        } catch (e: Exception) {
            ErrorCollector.report(e, "SnapshotStore.readRef($namespace/$name)")
            emptyList()
        }
    }

    private fun writeRef(namespace: String, name: String, ids: List<String>) {
        val file = refFile(namespace, name)
        try {
            file.parentFile?.mkdirs()
            val suffix = if (ids.isEmpty()) "" else "\n"
            ObjectStore.writeTextAtomic(file, ids.joinToString("\n", postfix = suffix))
        } catch (e: Exception) {
            ErrorCollector.report(e, "SnapshotStore.writeRef($namespace/$name)")
        }
    }

    private fun refFile(namespace: String, name: String): File {
        val safe = namespace.replace(REF_UNSAFE, "_").ifBlank { "default" }
        return File(File(DataPaths.TWIN_STORE, "refs"), "$safe/$name")
    }

    private fun readClock(): Long = try {
        File(DataPaths.TWIN_CLOCK).readText().trim().toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun nextLamport(namespace: String, parents: List<String>): Long {
        val parentMax = parents.mapNotNull { snapshot(namespace, it)?.lamport }.maxOrNull() ?: 0L
        val next = maxOf(readClock(), parentMax) + 1
        try {
            val file = File(DataPaths.TWIN_CLOCK)
            file.parentFile?.mkdirs()
            ObjectStore.writeTextAtomic(file, "$next\n")
        } catch (e: Exception) {
            ErrorCollector.report(e, "SnapshotStore.nextLamport")
        }
        return next
    }
}
