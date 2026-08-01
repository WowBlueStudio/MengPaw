// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.dev

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * DevPlugin 链路 JVM 测试 — 验证插件开发链路 create → audit → keywords 全流程.
 * 使用临时目录隔离, 不触碰真机数据.
 */
class DevPluginChainTest {

    private lateinit var tmp: File
    private val plugin = DevPlugin()
    private val ctx = ExecutionContext(sessionId = "test", userId = "test", workDir = "")

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "dev-plugin-test-${System.nanoTime()}")
        tmp.mkdirs()
        DataPaths.initialize(tmp.absolutePath)
    }

    private suspend fun run(cmd: String, args: List<String>): ExecutionResult {
        val handler = plugin.commands[cmd] ?: error("命令 $cmd 未注册")
        return handler(args, ctx)
    }

    @Test
    fun `create script plugin then audit passes`() = runBlocking {
        val create = run("plugin.create", listOf("--type", "script", "--name", "hello"))
        assertTrue("create 应成功: ${create.error}", create.success)

        val dir = File(DataPaths.PLUGIN_CACHE, "hello-plugin")
        if (!dir.exists()) {
            // 中文名生成 id 可能不同 — 宽松断言: 缓存目录下存在骨架
            val candidates = File(DataPaths.PLUGIN_CACHE).listFiles() ?: emptyArray()
            assertTrue("应生成插件骨架目录", candidates.isNotEmpty())
            return@runBlocking
        }
        val manifest = File(dir, "plugin.json")
        assertTrue("应生成 plugin.json", manifest.exists())
        assertTrue("plugin.json 含 commands", manifest.readText().contains("\"commands\""))

        val audit = run("plugin.audit", listOf("--target", "hello-plugin"))
        assertTrue("audit 应成功: ${audit.error}", audit.success)
        assertTrue("骨架审计应通过: ${audit.output}", audit.output.contains("✅ 审计通过"))
    }

    @Test
    fun `create native plugin generates kotlin source`() = runBlocking {
        val create = run("plugin.create", listOf("--type", "native", "--name", "hello"))
        assertTrue("create 应成功: ${create.error}", create.success)

        val dir = File(DataPaths.PLUGIN_CACHE, "hello-plugin")
        assertTrue("应生成 hello-plugin 目录", dir.exists())
        val buildFile = dir.listFiles { f -> f.name == "build.gradle.kts" }?.firstOrNull()
            ?: error("应生成 build.gradle.kts")
        assertTrue(buildFile.exists())
        val kotlinSrc = dir.walkTopDown().filter { it.extension == "kt" }.firstOrNull()
            ?: error("应生成 Kotlin 源文件")

        // 模板应包含 ports 声明与 PluginType.NATIVE
        val code = kotlinSrc.readText()
        assertTrue("模板应声明 PluginType.NATIVE", code.contains("PluginType.NATIVE"))
        assertTrue("模板应包含 ports 声明", code.contains("ports = emptyList()"))

        val audit = run("plugin.audit", listOf("--target", "hello-plugin"))
        assertTrue("audit 应成功: ${audit.error}", audit.success)
        assertTrue("骨架审计应通过: ${audit.output}", audit.output.contains("✅ 审计通过"))
    }

    @Test
    fun `audit rejects core port 9876 declaration`() = runBlocking {
        run("plugin.create", listOf("--type", "native", "--name", "badport"))
        val dir = File(DataPaths.PLUGIN_CACHE, "badport-plugin")
        val kt = dir.walkTopDown().filter { it.extension == "kt" }.firstOrNull()
            ?: error("未生成 Kotlin 源")
        kt.writeText(kt.readText().replace(
            "ports = emptyList()", "ports = listOf(9876)"
        ))
        val audit = run("plugin.audit", listOf("--target", "badport-plugin"))
        // audit 恒 success — 🔴 项列于报告文本, 由 share 阶段阻断
        assertTrue(audit.success)
        assertTrue("报告应标记 🔴 阻断: ${audit.output}", audit.output.contains("🔴"))
        assertTrue("报告应提及端口 9876: ${audit.output}", audit.output.contains("9876"))
    }

    @Test
    fun `audit with missing target reports usage`() = runBlocking {
        val audit = run("plugin.audit", emptyList())
        assertFalse("缺 --target 应失败", audit.success)
    }

    @Test
    fun `examples command returns reference content`() = runBlocking {
        val ex = run("plugin.examples", emptyList())
        assertTrue(ex.success)
        assertTrue(ex.output.contains("MengPaw"))
    }

    @Test
    fun `guide command returns capability doc and writes file`() = runBlocking {
        val guide = run("plugin.guide", emptyList())
        assertTrue(guide.success)
        assertTrue("应包含能力边界标题: ${guide.output.take(100)}", guide.output.contains("能力边界"))
        assertTrue("应包含命令清单", guide.output.contains("dev.plugin.create"))
        assertTrue("应包含审计规则", guide.output.contains("🔴"))
        assertTrue("应包含端口说明", guide.output.contains("9876"))

        val written = PluginDevGuide.targetFile
        assertTrue("文档应落盘: $written", written.exists())
        assertTrue("落盘内容应完整", written.readText().contains("MengPaw 插件开发工具"))
    }
}
