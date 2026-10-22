// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备索引生成 (docs/memory-git-sync-design.md §7.6 / §8.1) — `{AGENTS}/twin/devices/index.md`。
 *
 * 每设备一行: 名称 · 形态 · 模型 · 关键能力 · 时效; 首行"自动生成，勿手改"。
 * 索引是**本机生成物**: 时效文案 (X 天前) 随时间变化, 因此**不参与 `dev` 命名空间同步**
 * (见 `TwinSyncSpaces.DevicesSpace`), 各端按自己的库存行本地重算 —— 否则 D 类单写者
 * 会让两台设备争同一路径, 生成物反过来制造同步抖动。
 */
object DeviceIndexWriter {

    const val INDEX_NAME = "index.md"
    private const val HEADER = "# 自动生成，勿手改 — devices/index.md"
    private const val SELF_MARK = "本机"
    private const val RETIRED_MARK = "已退役"

    fun indexFile(): File = File(DataPaths.TWIN_DEVICES, INDEX_NAME)

    /** 当前索引原文 (缺失/损坏 → 空串)。 */
    fun read(): String {
        val file = indexFile()
        if (!file.isFile) return ""
        return try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "DeviceIndexWriter.read")
            ""
        }
    }

    /** 渲染索引 (纯函数, 测试可直接断言)。排序: 本机 → 其余按名称 → 退役行垫底。 */
    fun render(
        devices: List<DeviceInventory>,
        localId: String = DeviceInventoryStore.localDeviceId(),
        now: Long = System.currentTimeMillis()
    ): String {
        val ordered = devices.sortedWith(
            compareBy<DeviceInventory> { if (it.deviceId == localId) 0 else 1 }
                .thenBy { if (it.isRetired) 1 else 0 }
                .thenBy { it.displayName }
        )
        return buildString {
            appendLine(HEADER)
            appendLine("> 设备: ${ordered.size} · 更新: ${timeText(now)}")
            appendLine()
            if (ordered.isEmpty()) {
                appendLine("(暂无库存 — 执行 device.refresh 登记本机)")
                return@buildString
            }
            ordered.forEach { appendLine(row(it, localId, now)) }
        }.trimEnd() + "\n"
    }

    /** 单行渲染 (公开以便测试逐字段断言)。 */
    fun row(inv: DeviceInventory, localId: String = DeviceInventoryStore.localDeviceId(), now: Long = System.currentTimeMillis()): String {
        val model = inv.agents.map { it.model }.firstOrNull { it.isNotBlank() }.orEmpty()
        val vision = inv.agents.any { it.vision == true }
        return buildString {
            append("- ")
            append(inv.hardwareLine())
            if (model.isNotBlank()) append(" · ").append(model)
            if (vision) append("（视觉）")
            append(" · ").append(DeviceInventoryStore.ageText(inv, now))
            if (inv.deviceId == localId) append(" · ").append(SELF_MARK)
            if (inv.isRetired) append(" · ").append(RETIRED_MARK)
        }
    }

    /**
     * 重建索引并原子落盘; 内容未变化不重写 (避免 mtime 抖动 → 提示词无谓失效)。
     * 调用时机: 本机库存写入后 ([DeviceInventoryStore.write]) 与对端库存行同步落盘后
     * (`TwinSyncSpaces.DevicesSpace.write`)。失败 → null (不抛)。
     */
    @Synchronized
    fun rebuild(now: Long = System.currentTimeMillis()): String? {
        return try {
            val content = render(DeviceInventoryStore.readAll(), now = now)
            val target = indexFile()
            if (target.isFile && target.readText() == content) return content
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$INDEX_NAME.tmp")
            try {
                tmp.writeText(content)
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (tmp.exists()) try { tmp.delete() } catch (_: Exception) { /* best-effort */ }
            }
            content
        } catch (e: Exception) {
            ErrorCollector.report(e, "DeviceIndexWriter.rebuild")
            null
        }
    }

    /** 索引里是否登记了该能力 (供 `device.find` 无库存时兜底检索)。 */
    fun findIn(text: String, capability: String): List<String> {
        val needle = capability.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return text.lineSequence()
            .filter { it.startsWith("- ") && it.lowercase().contains(needle) }
            .toList()
    }

    private fun timeText(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
}
