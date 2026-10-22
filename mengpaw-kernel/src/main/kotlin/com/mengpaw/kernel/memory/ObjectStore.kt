// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * 对象库底层存储 (P1) — 路径分桶、zlib 编解码、原子落位、损坏防御。
 *
 * 从 [SnapshotStore] 拆出 (400 行文件拆分): 本对象只关心"字节如何落盘/读回",
 * 不解析 blob/tree/snapshot 语义。
 */
internal object ObjectStore {

    private const val MAX_OBJECT_BYTES = 64 * 1024 * 1024 // 解压上限, 防压缩炸弹
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val tmpSeq = AtomicLong(0)

    private val storeRoot: File get() = File(DataPaths.TWIN_STORE)
    private val objectsRoot: File get() = File(storeRoot, "objects")
    private val tmpRoot: File get() = File(storeRoot, "tmp")

    fun isObjectId(id: String): Boolean = HEX64.matches(id)

    /** 写入对象 (内容寻址, 幂等), 返回 SHA-256。 */
    fun write(payload: ByteArray): String {
        val sha = sha256Hex(payload)
        val target = objectFile(sha)
        if (target.isFile) return sha
        try {
            target.parentFile?.mkdirs()
            tmpRoot.mkdirs()
            val tmp = File(tmpRoot, "$sha.${tmpSeq.incrementAndGet()}.tmp")
            tmp.writeBytes(zlib(payload))
            moveAtomic(tmp, target)
        } catch (e: Exception) {
            ErrorCollector.report(e, "ObjectStore.write($sha)")
        }
        return sha
    }

    /** 读取并校验对象; 缺失 → null; 解压/校验失败 → 记录并 null。 */
    fun read(id: String): ByteArray? {
        if (!isObjectId(id)) return null
        val file = objectFile(id)
        if (!file.isFile) return null
        return try {
            val raw = inflate(file.readBytes())
            if (sha256Hex(raw) != id) throw IOException("对象校验失败: $id")
            raw
        } catch (e: Exception) {
            reportCorrupt("read", id, e)
            null
        }
    }

    /** 枚举全部对象 ID (ab/cdef… → abcdef…)。 */
    fun listIds(): List<String> {
        val dirs = try { objectsRoot.listFiles()?.filter { it.isDirectory } } catch (_: Exception) { null }
            ?: return emptyList()
        val ids = mutableListOf<String>()
        for (dir in dirs) {
            val files = try { dir.listFiles()?.filter { it.isFile } } catch (_: Exception) { null } ?: continue
            for (file in files) {
                val id = dir.name + file.name
                if (HEX64.matches(id)) ids.add(id)
            }
        }
        return ids
    }

    fun sizeBytes(): Long = listIds().sumOf { id ->
        try { objectFile(id).length() } catch (_: Exception) { 0L }
    }

    fun objectFile(id: String): File = File(File(objectsRoot, id.take(2)), id.drop(2))

    /** 删除对象; 返回是否真删。 */
    fun delete(id: String): Boolean = try { objectFile(id).delete() } catch (_: Exception) { false }

    /** 原子写纯文本 (refs/clock 用)。 */
    fun writeTextAtomic(target: File, text: String) {
        tmpRoot.mkdirs()
        val tmp = File(tmpRoot, "${target.name}.${tmpSeq.incrementAndGet()}.tmp")
        tmp.writeText(text)
        moveAtomic(tmp, target)
    }

    /**
     * 先 rename 覆盖 (POSIX 上目标不会短暂消失); 失败回退为复制覆盖 —
     * 绝不先删目标; 两者都失败时保留 tmp 待恢复。
     */
    fun moveAtomic(tmp: File, target: File): Boolean {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (e: Exception) {
            ErrorCollector.report(e, "ObjectStore.moveAtomic(${target.name})")
        }
        return try {
            Files.copy(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try { tmp.delete() } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            ErrorCollector.report(e, "ObjectStore.moveAtomic.copy(${target.name})")
            false
        }
    }

    fun reportCorrupt(op: String, id: String, e: Exception) {
        ErrorCollector.report(IOException("对象损坏 ($op ${id.take(12)}): ${e.message}", e), "ObjectStore.$op")
    }

    private fun zlib(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(payload) }
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_OBJECT_BYTES) throw IOException("对象解压超限")
            }
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
