// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 命名空间权威推导测试 — FIX(自检报告 P0-1):
 * browser-mcp-plugin 命令键自带 "mcp." 前缀 → ns 取 "browser" 拼出 browser.mcp.*;
 * browser-search-plugin 命令键为短名 → ns 取 "search" 拼出 search.clean/md/...;
 * memory-twin-plugin → "twin"; 普通插件去 "-plugin"/"-ext" 后缀。
 */
class PluginNamespaceTest {

    @Test
    fun `browser-mcp plugin maps to browser namespace`() {
        assertEquals("browser", pluginNamespaceFor("browser-mcp-plugin"))
        // 验证拼接语义: browser + "." + "mcp.tools" = browser.mcp.tools
        assertEquals("browser.mcp.tools", "browser" + "." + "mcp.tools")
    }

    @Test
    fun `browser-search plugin maps to search namespace`() {
        assertEquals("search", pluginNamespaceFor("browser-search-plugin"))
        assertEquals("search.clean", "search" + "." + "clean")
    }

    @Test
    fun `memory-twin plugin maps to twin namespace`() {
        assertEquals("twin", pluginNamespaceFor("memory-twin-plugin"))
    }

    @Test
    fun `plain plugins strip suffix only`() {
        assertEquals("root", pluginNamespaceFor("root-plugin"))
        assertEquals("tavily", pluginNamespaceFor("tavily-plugin"))
        assertEquals("fs", pluginNamespaceFor("fs-plugin"))
        assertEquals("framework", pluginNamespaceFor("framework-plugin"))
        assertEquals("custom-ext", pluginNamespaceFor("custom-ext-ext"))
    }
}
