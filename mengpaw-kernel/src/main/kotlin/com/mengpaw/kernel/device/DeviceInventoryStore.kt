// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 库存写入结果 — [UNCHANGED] 用于 `device.refresh` 的"有差异才写"。 */
enum class InventoryWrite { WRITTEN, UNCHANGED, REJECTED_FOREIGN, FAILED }

/**
 * 设备库存读写 (`{AGENTS}/twin/devices/<deviceId>.md`, docs/memory-git-sync-design.md §7)。
 *
 * **单写者校验落点**: [write] 只接受 `deviceId == 本机 deviceId` 的行; 其他端的行一律只读
 * (§7.2 单写者铁律 —— 工具层拒绝, 不靠提示词自觉)。本机 id 来源单一: [localDeviceId]。
 *
 * TODO(P5): 身份改 UUID 需与配对迁移同批 — 届时 [localDeviceId] 改读 `twin/device-id.json`,
 * 存量 `<指纹>.md` 需改名迁移, 否则同一台设备会出现两行。
 */
object DeviceInventoryStore {

    /** 时效阈值: 超过 30 天标"可能过时", 但**仍参与路由** (§7.6)。 */
    const val STALE_AFTER_DAYS = 30L

    private const val INDEX_NAME = "index.md"
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

    fun devicesDir(): File = File(DataPaths.TWIN_DEVICES)

    /**
     * 本机 deviceId — 与其他孪生组件同一来源 (`AcpCrypto.myFingerprint()`, 32 字符指纹)。
     * 取不到时回落 `local` (仅测试/非 Android 宿主; 单写者校验仍然生效)。
     */
    fun localDeviceId(): String = try {
        com.mengpaw.kernel.acp.AcpCrypto.myFingerprint().ifBlank { "local" }
    } catch (_: Exception) {
        "local"
    }

    /** deviceId → 文件名; 非法 id (含路径分隔符/超长/点) → null, 拒绝路径穿越。 */
    fun fileNameOf(deviceId: String): String? {
        val id = deviceId.trim()
        if (id.isEmpty() || id == "." || id == ".." || id.contains('.')) return null
        if (!SAFE_ID.matches(id)) return null
        return "$id.md"
    }

    /** 读取单行 (文件缺失/坏 frontmatter → null)。 */
    fun read(deviceId: String): DeviceInventory? {
        val name = fileNameOf(deviceId) ?: return null
        val file = File(devicesDir(), name)
        if (!file.isFile) return null
        val text = try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "DeviceInventoryStore.read($name)")
            return null
        }
        return DeviceInventory.parse(text)
    }

    /** 全部库存行 (跳过 index.md 与合并产物), 按名称排序 — 输出稳定可断言。 */
    fun readAll(): List<DeviceInventory> {
        val dir = devicesDir()
        if (!dir.isDirectory) return emptyList()
        val files = try { dir.listFiles()?.sortedBy { it.name } } catch (e: Exception) {
            ErrorCollector.report(e, "DeviceInventoryStore.readAll")
            null
        } ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isFile || !isInventoryName(file.name)) return@mapNotNull null
            val text = try { file.readText() } catch (e: Exception) {
                ErrorCollector.report(e, "DeviceInventoryStore.readAll(${file.name})")
                return@mapNotNull null
            }
            DeviceInventory.parse(text)
        }.sortedBy { it.displayName }
    }

    /** 本机库存行 (未登记 → null)。 */
    fun readLocal(): DeviceInventory? = read(localDeviceId())

    /**
     * 写一行库存。**只允许写本机行**: [localId] 之外的行返回 [InventoryWrite.REJECTED_FOREIGN]。
     *
     * 写入前与现有行做 agents 合并 (同 agent 取 `observedAt` 较新者) 并保留人读正文;
     * 文本完全一致 → [InventoryWrite.UNCHANGED] (不落盘、不动 mtime, 避免无谓同步历史)。
     */
    @Synchronized
    fun write(inv: DeviceInventory, localId: String = localDeviceId()): InventoryWrite {
        val name = fileNameOf(inv.deviceId) ?: return InventoryWrite.FAILED
        if (inv.deviceId != localId) return InventoryWrite.REJECTED_FOREIGN
        val dir = devicesDir()
        if (!dir.isDirectory && !dir.mkdirs()) return InventoryWrite.FAILED
        val target = File(dir, name)
        val existing = if (target.isFile) read(inv.deviceId) else null
        val merged = inv.copy(
            agents = mergeAgents(existing?.agents.orEmpty(), inv.agents),
            body = if (inv.body.isBlank()) existing?.body.orEmpty() else inv.body
        )
        val text = DeviceInventory.serialize(merged)
        val current = try { if (target.isFile) target.readText() else null } catch (e: Exception) {
            ErrorCollector.report(e, "DeviceInventoryStore.write.read($name)")
            null
        }
        if (current == text) return InventoryWrite.UNCHANGED
        val tmp = File(dir, ".$name.${System.nanoTime()}.tmp")
        return try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            DeviceIndexWriter.rebuild()
            InventoryWrite.WRITTEN
        } catch (e: Exception) {
            ErrorCollector.report(e, "DeviceInventoryStore.write($name)")
            InventoryWrite.FAILED
        } finally {
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * agents 合并: 同 name → 取 `observedAt` 较新的一侧 (空时间视为最旧);
     * 仅一侧有的 name 全保留。输出顺序 = 旧表顺序 + 新增名 (稳定, 便于 diff)。
     */
    fun mergeAgents(existing: List<AgentEntry>, incoming: List<AgentEntry>): List<AgentEntry> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        val out = LinkedHashMap<String, AgentEntry>()
        existing.forEach { out[it.name] = it }
        incoming.forEach { next ->
            val prev = out[next.name]
            out[next.name] = if (prev == null) next else if (newer(next.observedAt, prev.observedAt)) next else prev
        }
        return out.values.toList()
    }

    /** `a` 是否比 `b` 更新 (ISO 时间解析失败时按字面比较, 空串最旧)。 */
    fun newer(a: String, b: String): Boolean {
        val ia = observedInstant(a)
        val ib = observedInstant(b)
        if (ia != null && ib != null) return ia.isAfter(ib)
        if (ia != null) return true
        if (ib != null) return false
        return a.trim() > b.trim()
    }

    /** 解析 ISO-8601 (带偏移 / 仅本地时间 / Instant 三种形态); 失败 → null。 */
    fun observedInstant(observedAt: String): Instant? {
        val v = observedAt.trim()
        if (v.isEmpty()) return null
        try {
            return OffsetDateTime.parse(v).toInstant()
        } catch (_: Exception) { /* 继续尝试 */ }
        try {
            return Instant.parse(v)
        } catch (_: Exception) { /* 继续尝试 */ }
        return try {
            LocalDateTime.parse(v).toInstant(ZoneOffset.ofHours(8))
        } catch (_: Exception) { null }
    }

    /** 距今天数 (未来时间按 0); 无法解析 → null。 */
    fun ageDays(observedAt: String, now: Long = System.currentTimeMillis()): Long? {
        val instant = observedInstant(observedAt) ?: return null
        val diff = now - instant.toEpochMilli()
        return if (diff <= 0) 0 else diff / 86_400_000L
    }

    /** 是否过期 (> 30 天)。时间缺失按"未过期"处理 — 缺时间不等于过期。 */
    fun isStale(inv: DeviceInventory, now: Long = System.currentTimeMillis()): Boolean {
        val instant = observedInstant(inv.observedAt) ?: return false
        return now - instant.toEpochMilli() > STALE_AFTER_DAYS * 86_400_000L
    }

    /** 时效文案: `刚刚更新` / `N 分钟前更新` / `N 小时前更新` / `N 天前更新` / `无时间记录`。 */
    fun ageText(inv: DeviceInventory, now: Long = System.currentTimeMillis()): String {
        val instant = observedInstant(inv.observedAt) ?: return "无时间记录"
        val diff = now - instant.toEpochMilli()
        if (diff <= 0) return "刚刚更新"
        val minutes = diff / 60_000L
        return when {
            minutes < 1 -> "刚刚更新"
            minutes < 60 -> "${minutes} 分钟前更新"
            minutes < 1440 -> "${minutes / 60} 小时前更新"
            else -> "${minutes / 1440} 天前更新"
        }
    }

    /** 目录里的 md 是否为库存行 (排除生成索引与合并产物)。 */
    fun isInventoryName(name: String): Boolean =
        name.endsWith(".md") && name != INDEX_NAME &&
            !name.startsWith(".") && !name.contains(".deleted.") &&
            !name.contains(".conflict.") && !name.contains(".kept-") && !name.endsWith(".tmp")
}
