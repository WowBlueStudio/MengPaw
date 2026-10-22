// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

/**
 * 对象库数据模型 (P1, docs/memory-git-sync-design.md §3)。
 *
 * 对象只有三种, 全部内容寻址 (SHA-256):
 * - **blob**: 工作区文件内容 (带 `blob <len>\0` 头, 同 git 的可读格式)
 * - **tree**: v1 扁平清单 `relPath -> blobSha` 的 JSON (不做层级 tree)
 * - **snapshot**: 一次快照的元数据 (`tree` + `parents` + 作者/时钟/说明)
 *
 * 工作区文件永远是活状态, 对象库只是追加快照日志 — 没有 checkout / index,
 * Agent 不需要"记得提交"。
 */

/** 清单条目: 工作区相对路径 → 内容 blob 的 SHA-256。 */
data class ManifestBlob(val relPath: String, val blobId: String)

/**
 * 一个不可变快照。
 *
 * @param id      快照对象自身 SHA-256 (内容寻址)
 * @param tree    清单对象 SHA-256
 * @param parents 父快照 ID (首个父 = 对比基准; 合并快照可多父)
 * @param lamport Lamport 逻辑时钟 (P2 合并排序依据)
 * @param wall    墙钟毫秒 (仅展示; 不参与因果判定)
 * @param author  Agent 名 (§3.2 的 author.agent)
 * @param message 提交说明
 * @param device  来源设备 ID (§3.2 的 author.deviceId; P1 无设备身份时回落 `local`)
 */
data class Snapshot(
    val id: String,
    val tree: String,
    val parents: List<String>,
    val lamport: Long,
    val wall: Long,
    val author: String,
    val message: String,
    val device: String = ""
) {
    /** 短 ID — 命令输出用, 避免刷屏。 */
    val shortId: String get() = id.take(8)

    companion object {
        /** 短 ID 长度 (与 twin.status / memory.log 展示一致)。 */
        const val SHORT_ID_CHARS = 8
    }
}

/** 清单条目列表 ↔ `relPath -> blobId` 映射 (tree 编解码与命令层共用)。 */
fun List<ManifestBlob>.toFileMap(): Map<String, String> =
    associate { it.relPath to it.blobId }

/** `relPath -> blobId` 映射 → 清单条目列表 (按路径排序, 保证可读)。 */
fun Map<String, String>.toManifestBlobs(): List<ManifestBlob> =
    toSortedMap().map { (relPath, blobId) -> ManifestBlob(relPath, blobId) }
