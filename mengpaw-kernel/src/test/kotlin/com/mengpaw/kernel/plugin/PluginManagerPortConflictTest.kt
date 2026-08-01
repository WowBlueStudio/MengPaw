// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginManagerPortConflictTest {

    private fun plugin(id: String, ports: List<Int>): Plugin = object : Plugin {
        override val metadata = PluginMetadata(
            id = id, name = id, version = "1.0.0", author = "test",
            minCoreVersion = "0.2.0",
            ports = ports
        )
        override val commands: Map<String, CommandHandler> = emptyMap()
        override val uiButtons: List<PluginUiButton> = emptyList()
        override suspend fun onInstall(context: PluginContext) {}
        override suspend fun onUninstall() {}
        override suspend fun onUpgrade(newVersion: String) {}
    }

    @Test
    fun `same port declared by two plugins - second install fails`() = runBlocking {
        val pm = PluginManager("0.20.0")
        val a = plugin("a-plugin", listOf(8188))
        val b = plugin("b-plugin", listOf(8188))

        assertTrue(pm.install(a).isSuccess)
        val result = pm.install(b)
        assertTrue("同端口插件应被拒绝安装", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("8188") == true)
    }

    @Test
    fun `different ports install fine`() = runBlocking {
        val pm = PluginManager("0.20.0")
        assertTrue(pm.install(plugin("a-plugin", listOf(8188))).isSuccess)
        assertTrue(pm.install(plugin("b-plugin", listOf(9000))).isSuccess)
    }

    @Test
    fun `release port via uninstall then reinstall succeeds`() = runBlocking {
        val pm = PluginManager("0.20.0")
        val a = plugin("a-plugin", listOf(8188))
        val b = plugin("b-plugin", listOf(8188))
        pm.install(a)
        pm.uninstall("a-plugin")
        assertTrue("端口释放后应可安装", pm.install(b).isSuccess)
    }

    @Test
    fun `no ports declared never conflicts`() = runBlocking {
        val pm = PluginManager("0.20.0")
        assertTrue(pm.install(plugin("a-plugin", emptyList())).isSuccess)
        assertTrue(pm.install(plugin("b-plugin", emptyList())).isSuccess)
        assertTrue(pm.install(plugin("c-plugin", listOf(9999))).isSuccess)
    }

    @Test
    fun `invalid port range is ignored in conflict detection`() = runBlocking {
        // 越界端口 (0, 70000) 不参与冲突检测 — 与 install 实现一致
        val pm = PluginManager("0.20.0")
        assertTrue(pm.install(plugin("a-plugin", listOf(0, 70000))).isSuccess)
        assertTrue(pm.install(plugin("b-plugin", listOf(0, 70000))).isSuccess)
    }
}
