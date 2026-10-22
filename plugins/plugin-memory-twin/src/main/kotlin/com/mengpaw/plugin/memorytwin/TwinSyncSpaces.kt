// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.SyncV3Messages
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.memory.MemoryIndexWriter
import com.mengpaw.kernel.memory.MemoryLedger
import com.mengpaw.kernel.memory.SnapshotStore
import com.mengpaw.kernel.memory.TreeMerge
import com.mengpaw.kernel.memory.TwinFileClass
import com.mengpaw.kernel.memory.WorkspaceSnapshotSource
import java.io.File

/** 一次空间写入的结果 (计数用: 只有 [APPLIED] 才算真正落盘; [REJECTED] = 单写者策略拒绝)。 */
internal enum class SpaceWriteResult { APPLIED, SAME, FAILED, REJECTED }

/** 一次空间删除的结果 — [REJECTED] 表示单写者策略拒绝 (调用方须记录, 不得静默)。 */
internal enum class SpaceDeleteResult { DONE, FAILED, REJECTED }

/**
 * v0.3 同步的"命名空间适配件" — 同一套 HELLO/ACK/TREE_WALK/OBJ_GET 消息,
 * 只需换扫描/写入实现 (`ws` 工作区 vs `dev` 设备库存, 见设计 §4.2)。
 */
internal interface SyncSpace {
    val namespace: String
    fun scan(): Map<String, ByteArray>
    /** 当前内容写入对象库后的 `relPath → blobId` (可直接交给 SnapshotStore.commit)。 */
    fun snapshot(): Map<String, String>
    fun write(relPath: String, bytes: ByteArray, fromDevice: String): SpaceWriteResult
    fun delete(relPath: String, fromDevice: String): SpaceDeleteResult
    fun classOf(relPath: String): TwinFileClass
}

/** `ws-<agent>` 命名空间: 工作区文档 (扫描/写入均委托内核实现与 TwinWorkspace 消毒入口)。 */
internal class WorkspaceSpace(private val agentName: String) : SyncSpace {

    override val namespace: String = SyncV3Messages.workspaceNamespace(agentName)

    override fun scan(): Map<String, ByteArray> =
        WorkspaceSnapshotSource.scan(agentName).associate { it.relPath to it.bytes }

    override fun snapshot(): Map<String, String> = WorkspaceSnapshotSource.snapshotFiles(agentName)

    override fun write(relPath: String, bytes: ByteArray, fromDevice: String): SpaceWriteResult =
        when (TwinWorkspace.applyWorkspaceBytes(agentName, relPath, bytes, fromDevice, Long.MAX_VALUE)) {
            "applied" -> SpaceWriteResult.APPLIED
            "same" -> SpaceWriteResult.SAME
            else -> SpaceWriteResult.FAILED
        }

    override fun delete(relPath: String, fromDevice: String): SpaceDeleteResult {
        TwinWorkspace.removeWorkspaceFile(agentName, relPath, fromDevice)
        return SpaceDeleteResult.DONE
    }

    override fun classOf(relPath: String): TwinFileClass = TreeMerge.classify(relPath)
}

/**
 * `dev` 命名空间: `{AGENTS}/twin/devices/` 下的设备库存 md 文件 (P4 建库存文件, P2 先打通同步)。
 *
 * 单写者铁律 (§7.2): 文件名即归属 —— `<deviceId>.md` 只允许其所属端改写/删除。
 * 落盘层硬校验: 对端快照里出现**本机行**且内容不同 (或对端删了本机行) → 跳过并记录,
 * 绝不覆盖本机库存 (lamport 更高也不行); 第三方行是正常中继 (任一端都可能先带来别端的行),
 * 按合并结果落盘, 不误杀。该条与 D 类合并策略的关系: 合并决定"取哪一侧"，
 * 本类决定"有没有权限落盘" —— 权限判据是**文件行归属**, 不是传输侧身份。
 * `index.md` 是**本机生成物** (时效文案随时间变), 不参与同步 —— 否则各端争写同一路径会制造抖动。
 */
internal class DevicesSpace(
    /** 本机 deviceId (= 同步身份; 生产与 `DeviceInventoryStore.localDeviceId()` 同源)。 */
    private val localDeviceId: String
) : SyncSpace {

    override val namespace: String = SyncV3Messages.NS_DEVICES

    private val root: File get() = File(DataPaths.TWIN_DEVICES)

    override fun scan(): Map<String, ByteArray> {
        val dir = root
        if (!dir.isDirectory) return emptyMap()
        val files = try {
            dir.listFiles()?.sortedBy { it.name }
        } catch (e: Exception) {
            ErrorCollector.report(e, "DevicesSpace.scan")
            null
        } ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        for (file in files) {
            if (!file.isFile || !isInventoryName(file.name)) continue
            val safe = TwinWorkspace.sanitizeRelPath(file.name) ?: continue
            try {
                out[safe] = file.readBytes()
            } catch (e: Exception) {
                ErrorCollector.report(e, "DevicesSpace.read(${file.name})")
            }
        }
        return out
    }

    override fun snapshot(): Map<String, String> =
        scan().mapValues { SnapshotStore.writeBlob(it.value) }

    override fun write(relPath: String, bytes: ByteArray, fromDevice: String): SpaceWriteResult {
        val safe = safeName(relPath) ?: return SpaceWriteResult.FAILED
        return try {
            val dir = root.also { it.mkdirs() }
            val target = File(dir, safe)
            val existing = if (target.isFile) target.readBytes() else null
            if (existing != null && existing.contentEquals(bytes)) return SpaceWriteResult.SAME
            // 单写者 (§7.2): 本机行内容已被对端改写 → 拒绝落盘 (调用方写 inbox + ErrorCollector)
            if (isLocalRow(safe)) return SpaceWriteResult.REJECTED
            val tmp = File(dir, ".$safe.${System.nanoTime()}.tmp")
            tmp.writeBytes(bytes)
            var renamed = tmp.renameTo(target)
            if (!renamed) renamed = try { tmp.copyTo(target, overwrite = true); true } catch (_: Exception) { false }
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            if (renamed) {
                // P4: 对端库存行落盘后重建本机索引 (索引是本机生成物, 不参与同步)
                try { com.mengpaw.kernel.device.DeviceIndexWriter.rebuild() } catch (e: Exception) {
                    ErrorCollector.report(e, "DevicesSpace.indexRebuild")
                }
            }
            if (renamed) SpaceWriteResult.APPLIED else SpaceWriteResult.FAILED
        } catch (e: Exception) {
            ErrorCollector.report(e, "DevicesSpace.write($safe)")
            SpaceWriteResult.FAILED
        }
    }

    override fun delete(relPath: String, fromDevice: String): SpaceDeleteResult {
        val safe = safeName(relPath) ?: return SpaceDeleteResult.DONE
        val target = File(root, safe)
        if (!target.exists()) return SpaceDeleteResult.DONE
        // 单写者 (§7.2): 本机行只能由本机删 (退役 = 本机改 status), 对端删除主张一律拒绝
        if (isLocalRow(safe)) return SpaceDeleteResult.REJECTED
        return try {
            val backup = File(root, "$safe.deleted.${fromDevice}")
            if (!backup.exists()) target.renameTo(backup) else target.delete()
            // P4: 删行后重建索引 (索引是本机生成物)
            try { com.mengpaw.kernel.device.DeviceIndexWriter.rebuild() } catch (e: Exception) {
                ErrorCollector.report(e, "DevicesSpace.indexRebuild")
            }
            SpaceDeleteResult.DONE
        } catch (e: Exception) {
            ErrorCollector.report(e, "DevicesSpace.delete($safe)")
            SpaceDeleteResult.FAILED
        }
    }

    override fun classOf(relPath: String): TwinFileClass = TwinFileClass.D

    /** `<deviceId>.md` 的行归属是否为本机 (单写者唯一判据)。 */
    private fun isLocalRow(name: String): Boolean = name.removeSuffix(".md") == localDeviceId

    /** 只接受单层 `.md` 文件名 — 设备库存没有子目录, 拒绝对端用路径穿越写别处。 */
    private fun safeName(relPath: String): String? {
        val safe = TwinWorkspace.sanitizeRelPath(relPath) ?: return null
        if (safe.contains('/') || safe.contains('\\')) return null
        if (!isInventoryName(safe)) return null
        return safe
    }

    private fun isInventoryName(name: String): Boolean =
        name.endsWith(".md") &&
            name != com.mengpaw.kernel.device.DeviceIndexWriter.INDEX_NAME &&
            !name.contains(".deleted.") && !name.contains(".conflict.") &&
            !name.contains(".kept-") && !name.endsWith(".tmp")
}

/**
 * `ledger-<agent>` 命名空间 (P3, 设计 §6.1): `{AGENTS}/twin/ledger/<agent>/entries.jsonl`。
 *
 * 账本目录在 agent 工作区之外, 因此单列一个命名空间; 复用 v0.3 四消息。
 * 合并策略 = `TwinFileClass.JSONL` (按 entryId 并集, 与合并方向无关)。
 * 删除不生效 —— 账本只增, 历史条目由 supersedes/revision 表达。
 */
internal class LedgerSpace(private val agentName: String) : SyncSpace {

    private companion object {
        const val FILE_NAME = "entries.jsonl"
    }

    override val namespace: String = SyncV3Messages.ledgerNamespace(agentName)

    private val root: File get() = File(DataPaths.ledgerFile(agentName)).parentFile ?: File(DataPaths.TWIN_LEDGER)

    override fun scan(): Map<String, ByteArray> {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return emptyMap()
        return try {
            mapOf(FILE_NAME to file.readBytes())
        } catch (e: Exception) {
            ErrorCollector.report(e, "LedgerSpace.scan")
            emptyMap()
        }
    }

    override fun snapshot(): Map<String, String> =
        scan().mapValues { SnapshotStore.writeBlob(it.value) }

    override fun write(relPath: String, bytes: ByteArray, fromDevice: String): SpaceWriteResult {
        if (relPath != FILE_NAME) return SpaceWriteResult.FAILED
        val target = File(root, FILE_NAME)
        val existing = try { if (target.isFile) target.readBytes() else null } catch (_: Exception) { null }
        if (existing != null && existing.contentEquals(bytes)) return SpaceWriteResult.SAME
        if (!MemoryLedger.replaceFromSync(agentName, bytes)) return SpaceWriteResult.FAILED
        try { MemoryIndexWriter.rebuild(agentName) } catch (e: Exception) {
            ErrorCollector.report(e, "LedgerSpace.indexRebuild")
        }
        return SpaceWriteResult.APPLIED
    }

    /** 账本只增: 对端删除不传播 (历史条目靠 revision 表达, 不靠删行)。 */
    override fun delete(relPath: String, fromDevice: String): SpaceDeleteResult = SpaceDeleteResult.DONE

    override fun classOf(relPath: String): TwinFileClass = TwinFileClass.JSONL
}
