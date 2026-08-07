// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 插件市场磁盘快照持久化测试 (P2 离线降级, v0.34.0)。
 *
 * 快照 = 成功 fetch 后写入 cacheDir 的原始索引 JSON + 元信息;
 * 全部网络源失败时 loadSnapshot 降级 (重启后离线仍可浏览/安装)。
 * persistSnapshot/loadSnapshot 为 internal — 纯文件逻辑, 不触网。
 */
class MarketplaceSnapshotTest {

    private val sampleIndex = """
        {
          "marketplace": "MengPaw 插件市场",
          "version": 1,
          "updated": "2026-08-01",
          "plugins": [
            {"id": "demo-plugin", "name": "Demo", "version": "1.2.3", "type": "native",
             "description": "测试插件", "downloadUrl": "https://example.com/demo.aar",
             "minCoreVersion": "0.8.0", "commands": ["demo.run"]}
          ]
        }
    """.trimIndent()

    private fun newClient(dir: File) = PluginMarketplaceClient(cacheDir = dir)

    @Test
    fun 快照写入后读取roundtrip一致() {
        val dir = Files.createTempDirectory("mkt-snap-").toFile()
        try {
            val c1 = newClient(dir)
            c1.persistSnapshot("https://gitee.com/.../plugins.json", sampleIndex)
            assertTrue("快照文件应落盘", File(dir, PluginMarketplaceClient.SNAPSHOT_FILE_NAME).isFile)

            // 新进程视角: 重新构造 client (内存缓存为空), 快照仍可读
            val c2 = newClient(dir)
            val snap = c2.loadSnapshot()
            assertNotNull(snap)
            assertEquals("rawJson 应原样保留", sampleIndex, snap!!.rawJson)
            assertEquals("https://gitee.com/.../plugins.json", snap.source)
            assertTrue("savedAt 应为正时间戳", snap.savedAt > 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun 快照经parseIndex解析出插件() {
        val dir = Files.createTempDirectory("mkt-snap-").toFile()
        try {
            val c = newClient(dir)
            c.persistSnapshot("https://gitee.com/.../plugins.json", sampleIndex)
            val index = c.parseIndex(c.loadSnapshot()!!.rawJson)
            assertEquals(1, index.plugins.size)
            assertEquals("demo-plugin", index.plugins[0].id)
            assertEquals("1.2.3", index.plugins[0].version)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun 无快照文件返回null() {
        val dir = Files.createTempDirectory("mkt-snap-").toFile()
        try {
            assertNull(newClient(dir).loadSnapshot())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun 损坏快照返回null不抛异常() {
        val dir = Files.createTempDirectory("mkt-snap-").toFile()
        try {
            val f = File(dir, PluginMarketplaceClient.SNAPSHOT_FILE_NAME)
            f.writeText("{{{ not json")
            assertNull("损坏快照应静默返回 null", newClient(dir).loadSnapshot())
        } finally {
            dir.deleteRecursively()
        }
    }
}
