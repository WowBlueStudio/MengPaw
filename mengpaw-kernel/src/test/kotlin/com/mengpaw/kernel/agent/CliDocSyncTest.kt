// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.plugin.BuiltinPluginRegistry
import com.mengpaw.kernel.plugin.PluginManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * CLI.md 插件表链式检查 (v0.34.3 P0-1) — 生成文档中的插件 ID 必须全部来自
 * BuiltinPluginRegistry 注入集合; 历史硬编码幻影条目 (notification/workflow/
 * incubator/cdp/inspector/agent-mission/agent-loop) 永久缺席。
 * 防 CliDocGenerator 未来重新引入硬编码插件表。
 */
class CliDocSyncTest {

    @Before
    fun setup() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_clidoc_${System.nanoTime()}")
        BuiltinPluginRegistry.resetForTest()
    }

    @Test
    fun `cli doc plugin tables reference registered briefs only`() {
        BuiltinPluginRegistry.builtinBriefs = mapOf(
            "fs-plugin" to "文件系统",
            "net-plugin" to "网络请求"
        )
        BuiltinPluginRegistry.remoteBriefs = mapOf("update-plugin" to "自动更新")

        val pm = PluginManager()
        val mgr = AgentDocManager()
        mgr.pluginManager = pm
        AgentExecutor(mgr) // 注入 registeredAgentCommands
        mgr.regenerateCliDoc(pm)

        val doc = mgr.getDoc(AgentDocType.CLI)
        val ids = Regex("\\| ([a-z0-9-]+-plugin) \\|").findAll(doc).map { it.groupValues[1] }.toSet()
        val known = BuiltinPluginRegistry.builtinBriefs.keys + BuiltinPluginRegistry.remoteBriefs.keys
        assertTrue("CLI.md 插件表条目必须来自注册源: ${ids - known}", (ids - known).isEmpty())
        assertTrue("内置表应含 fs-plugin", ids.contains("fs-plugin"))
        assertTrue("远程表应含 update-plugin", ids.contains("update-plugin"))

        // 历史硬编码幻影条目必须永久缺席
        listOf(
            "notification-plugin", "workflow-plugin", "incubator-plugin",
            "cdp-plugin", "inspector-plugin", "agent-mission-plugin", "agent-loop-plugin"
        ).forEach { ghost ->
            assertFalse("幻影条目不得出现在 CLI.md: $ghost", doc.contains(ghost))
        }
    }
}
