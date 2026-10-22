// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * 工作区扫描 — 把 `{AGENTS}/{agentName}/` 的同步范围收敛成快照文件集 (P1)。
 *
 * 收录:
 * - 任意层级 `.md`;
 * - 根目录 `twin-model-rules.json` (模型能力规则, 需随孪生扩散)。
 *
 * 排除: `inbox/` `dialog/` `backup/` 目录、`CLI.md`、`*.tmp`、`*.conflict.*`。
 *
 * **P2 接缝**: plugin-memory-twin 已有等价逻辑 `TwinWorkspace.scanDir`。
 * 插件不能反向被 kernel 依赖, 故 P1 先在 kernel 落一份; P2 由插件改为
 * 委托本实现 (`WorkspaceSnapshotSource.scan`), 消除重复。
 */
object WorkspaceSnapshotSource {

    /** 不同步的目录名 (任意层级)。 */
    private val EXCLUDED_DIRS = setOf("inbox", "dialog", "backup")

    /** 不同步的根文件名 (保留用途: 已移除的 CLI 手册, 勿动)。 */
    private val EXCLUDED_FILES = setOf("CLI.md")

    /** 根目录额外收录的非 .md 文件。 */
    const val ROOT_RULES_FILE = "twin-model-rules.json"

    /** 一个待快照文件: 工作区相对路径 (正斜杠) + 原始字节。 */
    data class ScannedFile(val relPath: String, val bytes: ByteArray) {
        /** UTF-8 文本视图 (工作区同步范围只有 md/json, 不会丢字节)。 */
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    /** 扫描并读取文件内容 (不可读文件跳过并记错误, 不中断)。 */
    fun scan(agentName: String): List<ScannedFile> {
        val root = File(DataPaths.resolver.agentDir(agentName))
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<ScannedFile>()
        scanDir(root, "", out)
        return out.sortedBy { it.relPath }
    }

    /** 扫描并写入 blob, 返回 `relPath -> blobId` (可直接交给 [SnapshotStore.commit])。 */
    fun snapshotFiles(agentName: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        scan(agentName).forEach { result[it.relPath] = SnapshotStore.writeBlob(it.bytes) }
        return result
    }

    /** 扫描并返回文本视图 (diff 用); 不可读文件自动跳过。 */
    fun readTexts(agentName: String): Map<String, String> =
        scan(agentName).associate { it.relPath to it.text }

    private fun scanDir(dir: File, relDir: String, out: MutableList<ScannedFile>) {
        val children = try {
            dir.listFiles()?.sortedBy { it.name }
        } catch (e: Exception) {
            ErrorCollector.report(e, "WorkspaceSnapshotSource.scan(${dir.name})")
            null
        } ?: return

        for (file in children) {
            val rel = if (relDir.isEmpty()) file.name else "$relDir/${file.name}"
            if (file.isDirectory) {
                if (file.name in EXCLUDED_DIRS) continue
                scanDir(file, rel, out)
                continue
            }
            if (!isIncluded(file, relDir, rel)) continue
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                ErrorCollector.report(e, "WorkspaceSnapshotSource.read($rel)")
                continue
            }
            out.add(ScannedFile(rel, bytes))
        }
    }

    private fun isIncluded(file: File, relDir: String, rel: String): Boolean {
        if (relDir.isEmpty() && file.name in EXCLUDED_FILES) return false
        if (file.name.endsWith(".tmp")) return false
        if (file.name.contains(".conflict.")) return false
        if (file.name.endsWith(".md")) return true
        return relDir.isEmpty() && file.name == ROOT_RULES_FILE
    }
}
