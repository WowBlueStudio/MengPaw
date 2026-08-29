// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * toolSourceFor — 全局工具面板命令来源分类 (核心/插件)。
 * 语义: 内核 6 命名空间 = 核心, 其余 (插件注册/未知) = 插件。
 */
class ToolSourceClassifyTest {

    @Test
    fun `core namespaces classified as core`() {
        // PipelineManager 内置 5 个 + core 适配层 sys
        assertEquals("core", toolSourceFor("self.status"))
        assertEquals("core", toolSourceFor("self.notify.message"))
        assertEquals("core", toolSourceFor("evolution.audit"))
        assertEquals("core", toolSourceFor("agent.memory.keep"))
        assertEquals("core", toolSourceFor("plugin.install"))
        assertEquals("core", toolSourceFor("security.block"))
        assertEquals("core", toolSourceFor("sys.battery"))
    }

    @Test
    fun `plugin namespaces classified as plugin`() {
        assertEquals("plugin", toolSourceFor("tavily.search"))
        assertEquals("plugin", toolSourceFor("hermes.team"))
        assertEquals("plugin", toolSourceFor("fs.cp"))
        assertEquals("plugin", toolSourceFor("net.curl"))
        assertEquals("plugin", toolSourceFor("skill.ls"))
        assertEquals("plugin", toolSourceFor("clipboard.copy"))
        assertEquals("plugin", toolSourceFor("tribe.memo"))
        assertEquals("plugin", toolSourceFor("update.check"))
    }

    @Test
    fun `unknown namespace defaults to plugin`() {
        // 未知来源防御 — 非内核即插件, 缺省安全 (不会把新命令误标核心)
        assertEquals("plugin", toolSourceFor("unknown.cmd"))
        assertEquals("plugin", toolSourceFor("第三方.任意"))
    }
}
