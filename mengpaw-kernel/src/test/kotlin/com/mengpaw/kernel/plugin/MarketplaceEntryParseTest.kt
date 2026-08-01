// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import org.junit.Assert.*
import org.junit.Test

class MarketplaceEntryParseTest {

    @Test
    fun `parse entry with ports`() {
        val json = """
            {"marketplace":"test","version":5,"updated":"2026-07-31",
             "plugins":[{"id":"comfy-plugin","name":"ComfyUI","version":"1.0.0",
               "type":"native","author":"x","status":"remote",
               "downloadUrl":"https://example.com/releases/download/plugins-v0.21.0/comfy-plugin-1.0.0-release.aar",
               "commands":["comfy.run"],"ports":[8188]}]}
        """.trimIndent()
        val idx = PluginMarketplaceClient().parseIndex(json)
        assertEquals(listOf(8188), idx.plugins[0].ports)
    }

    @Test
    fun `parse entry without ports defaults to empty`() {
        val json = """
            {"marketplace":"test","version":5,"updated":"2026-07-31",
             "plugins":[{"id":"fs-plugin","name":"FS","version":"1.0.0",
               "type":"native","author":"x","status":"builtin","commands":["fs.ls"]}]}
        """.trimIndent()
        val idx = PluginMarketplaceClient().parseIndex(json)
        assertEquals(emptyList<Int>(), idx.plugins[0].ports)
    }

    @Test
    fun `parse entry with bad ports is tolerated`() {
        val json = """
            {"marketplace":"test","version":5,"updated":"2026-07-31",
             "plugins":[{"id":"x-plugin","name":"X","version":"1.0.0",
               "type":"native","author":"x","status":"remote",
               "downloadUrl":"https://example.com/a.aar",
               "commands":["x.run"],"ports":[8188,"bad",0,70000,9999]}]}
        """.trimIndent()
        val idx = PluginMarketplaceClient().parseIndex(json)
        // 非法值 (bad/0/70000) 被过滤, 合法值保留
        assertEquals(listOf(8188, 9999), idx.plugins[0].ports)
    }
}
