// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.SyncV3Messages
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.memory.SnapshotStore
import com.mengpaw.kernel.memory.TreeMerge
import com.mengpaw.kernel.memory.TwinFileClass
import com.mengpaw.kernel.memory.WorkspaceSnapshotSource
import java.io.File

/** 一次空间写入的结果 (计数用: 只有 [APPLIED] 才算真正落盘)。 */
internal enum class SpaceWriteResult { APPLIED, SAME, FAILED }

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
    fun delete(relPath: String, fromDevice: String)
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

    override fun delete(relPath: String, fromDevice: String) =
        TwinWorkspace.removeWorkspaceFile(agentName, relPath, fromDevice)

    override fun classOf(relPath: String): TwinFileClass = TreeMerge.classify(relPath)
}

/**
 * `dev` 命名空间: `{AGENTS}/twin/devices/` 下的设备库存 md 文件 (P4 建库存文件, P2 先打通同步)。
 *
 * 单写者铁律: 本机只应写自己那一行; 写入目标由合并器 D 类策略保证 (同 deviceId 双写会告警)。
 */
internal class DevicesSpace : SyncSpace {

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
            val tmp = File(dir, ".$safe.${System.nanoTime()}.tmp")
            tmp.writeBytes(bytes)
            var renamed = tmp.renameTo(target)
            if (!renamed) renamed = try { tmp.copyTo(target, overwrite = true); true } catch (_: Exception) { false }
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            if (renamed) SpaceWriteResult.APPLIED else SpaceWriteResult.FAILED
        } catch (e: Exception) {
            ErrorCollector.report(e, "DevicesSpace.write($safe)")
            SpaceWriteResult.FAILED
        }
    }

    override fun delete(relPath: String, fromDevice: String) {
        val safe = safeName(relPath) ?: return
        val target = File(root, safe)
        if (!target.exists()) return
        try {
            val backup = File(root, "$safe.deleted.${fromDevice}")
            if (!backup.exists()) target.renameTo(backup) else target.delete()
        } catch (e: Exception) {
            ErrorCollector.report(e, "DevicesSpace.delete($safe)")
        }
    }

    override fun classOf(relPath: String): TwinFileClass = TwinFileClass.D

    /** 只接受单层 `.md` 文件名 — 设备库存没有子目录, 拒绝对端用路径穿越写别处。 */
    private fun safeName(relPath: String): String? {
        val safe = TwinWorkspace.sanitizeRelPath(relPath) ?: return null
        if (safe.contains('/') || safe.contains('\\')) return null
        if (!isInventoryName(safe)) return null
        return safe
    }

    private fun isInventoryName(name: String): Boolean =
        name.endsWith(".md") &&
            !name.contains(".deleted.") && !name.contains(".conflict.") &&
            !name.contains(".kept-") && !name.endsWith(".tmp")
}
