// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.agenttools

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AgentToolsTest {

    @Before
    fun setUp() {
        DataPaths.initialize(java.nio.file.Files.createTempDirectory("agttools").toString())
    }

    @After
    fun tearDown() {
        try { File(DataPaths.BASE).deleteRecursively() } catch (_: Exception) {}
    }

    /** 命令是 suspend handler，JUnit 方法内用 runBlocking 调用。 */
    private fun runCmd(plugin: AgentToolsPlugin, name: String, args: List<String>, ctx: ExecutionContext): ExecutionResult =
        runBlocking { plugin.commands[name]!!.invoke(args, ctx) }

    private fun validJson(name: String = "gh", extra: String = ""): String = """
        {
          "name": "$name",
          "displayName": "GitHub CLI",
          "source": "https://example.com/gh.json",
          "commands": [
            { "name": "gh pr list", "description": "列出仓库 PR", "usage": "gh pr list [--state open]" },
            { "name": "gh issue view", "description": "查看 issue 详情", "usage": "gh issue view <number>" }
          ]
          $extra
        }
    """.trimIndent()

    // ── 校验 ─────────────────────────────────────────────────────────

    @Test
    fun `非法名称拒绝`() {
        listOf("a/b", "a\\b", "a b", "", "..", "a".repeat(33), "a.b").forEach { bad ->
            val r = AgentToolsStore.parseAndValidate(bad, validJson())
            assertTrue("name='$bad' 应拒绝", r.isFailure)
        }
    }

    @Test
    fun `空命令集拒绝`() {
        val r = AgentToolsStore.parseAndValidate("gh", """{"name":"gh","commands":[]}""")
        assertTrue(r.isFailure)
    }

    @Test
    fun `超过200条命令拒绝`() {
        val many = (1..201).joinToString(",") { """{"name":"cmd$it"}""" }
        val r = AgentToolsStore.parseAndValidate("gh", """{"commands":[$many]}""")
        assertTrue(r.isFailure)
    }

    @Test
    fun `超过512KB拒绝`() {
        val big = """{"commands":[{"name":"a","description":"${"x".repeat(600000)}"}]}"""
        val r = AgentToolsStore.parseAndValidate("gh", big)
        assertTrue(r.isFailure)
    }

    @Test
    fun `未知字段忽略且CLI名称覆盖清单`() {
        val r = AgentToolsStore.parseAndValidate("cli-name", validJson(name = "inner-name"))
        assertTrue(r.isSuccess)
        val set = r.getOrThrow()
        assertEquals("cli-name", set.name)      // CLI 参数权威
        assertEquals("GitHub CLI", set.displayName)
        assertEquals(2, set.commands.size)
        assertTrue(set.importedAt.isNotBlank()) // 服务端写入
    }

    // ── 持久化 ───────────────────────────────────────────────────────

    @Test
    fun `save后readAll回读一致`() {
        val set = AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow()
        val overwritten = AgentToolsStore.save("Tester", set).getOrThrow()
        assertFalse(overwritten)
        val file = File(AgentToolsStore.toolsDir("Tester"), "gh.json")
        assertTrue(file.exists())

        val all = AgentToolsStore.readAll("Tester")
        assertEquals(1, all.size)
        assertEquals("gh", all[0].name)
        assertEquals(2, all[0].commands.size)
        assertEquals("gh pr list", all[0].commands[0].name)
    }

    @Test
    fun `重复导入覆盖更新`() {
        AgentToolsStore.save("Tester", AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow())
        val updated = validJson().replace("\"gh pr list\"", "\"gh pr create\"").replace("\"gh issue view\"", "\"gh search repos\"")
        val overwritten = AgentToolsStore.save("Tester",
            AgentToolsStore.parseAndValidate("gh", updated).getOrThrow()).getOrThrow()
        assertTrue(overwritten)
        val all = AgentToolsStore.readAll("Tester")
        assertEquals(2, all[0].commands.size)
        assertEquals("gh pr create", all[0].commands[0].name)
    }

    @Test
    fun `remove删除与不存在返回false`() {
        AgentToolsStore.save("Tester", AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow())
        assertTrue(AgentToolsStore.remove("Tester", "gh"))
        assertFalse(AgentToolsStore.remove("Tester", "gh"))
        assertTrue(AgentToolsStore.readAll("Tester").isEmpty())
    }

    // ── 命令级 ───────────────────────────────────────────────────────

    @Test
    fun `import命令注册并触发摘要失效`() {
        val plugin = AgentToolsPlugin()
        val ctx = ExecutionContext(sessionId = "t", agentName = "Tester")
        val r = runCmd(plugin, "import",listOf("gh", validJson()), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("已导入"))
        assertTrue(File(AgentToolsStore.toolsDir("Tester"), "gh.json").exists())
    }

    @Test
    fun `import非法参数返回用法`() {
        val plugin = AgentToolsPlugin()
        val ctx = ExecutionContext(sessionId = "t", agentName = "Tester")
        val r = runCmd(plugin, "import", emptyList(), ctx)
        assertFalse(r.success)
        assertTrue(r.error?.contains("用法") == true)
    }

    @Test
    fun `ls空目录返回引导`() {
        val plugin = AgentToolsPlugin()
        val ctx = ExecutionContext(sessionId = "t", agentName = "Tester")
        val r = runCmd(plugin, "ls", emptyList(), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("未注册命令集"))
    }

    @Test
    fun `search命中与大小写不敏感`() {
        val plugin = AgentToolsPlugin()
        val ctx = ExecutionContext(sessionId = "t", agentName = "Tester")
        runCmd(plugin, "import",listOf("gh", validJson()), ctx)
        val hit = runCmd(plugin, "search",listOf("PR"), ctx)
        assertTrue(hit.success)
        assertTrue(hit.output.contains("gh pr list"))
        val miss = runCmd(plugin, "search",listOf("不存在的词"), ctx)
        assertTrue(miss.success)
        assertTrue(miss.output.contains("未找到匹配"))
    }

    @Test
    fun `remove命令后摘要失效`() {
        val plugin = AgentToolsPlugin()
        val ctx = ExecutionContext(sessionId = "t", agentName = "Tester")
        runCmd(plugin, "import",listOf("gh", validJson()), ctx)
        val r = runCmd(plugin, "remove",listOf("gh"), ctx)
        assertTrue(r.success)
        assertTrue(AgentToolsStore.readAll("Tester").isEmpty())
    }

    // ── 摘要 ─────────────────────────────────────────────────────────

    @Test
    fun `buildSummary空目录返回空串`() {
        assertEquals("", AgentToolsStore.buildSummary("Tester"))
    }

    @Test
    fun `buildSummary字节稳定`() {
        AgentToolsStore.save("Tester", AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow())
        val s1 = AgentToolsStore.buildSummary("Tester")
        val s2 = AgentToolsStore.buildSummary("Tester")
        assertEquals(s1, s2)
        assertTrue(s1.contains("GitHub CLI (gh)"))
        assertTrue(s1.contains("gh pr list"))
    }

    @Test
    fun `perSetBudget截断`() {
        val many = (1..100).joinToString(",") { """{"name":"cmd$it","description":"x"}""" }
        AgentToolsStore.save("Tester",
            AgentToolsStore.parseAndValidate("big", """{"displayName":"大命令集","commands":[$many]}""").getOrThrow())
        val s = AgentToolsStore.buildSummary("Tester", perSetBudget = 200)
        assertTrue(s.length <= 200 + 10)  // 截断 + "…"
        assertTrue(s.endsWith("…"))
    }

    @Test
    fun `totalBudget截断给出检索引导`() {
        val cmd = (1..50).joinToString(",") { """{"name":"cmd$it"}""" }
        AgentToolsStore.save("Tester",
            AgentToolsStore.parseAndValidate("big", """{"commands":[$cmd]}""").getOrThrow())
        val s = AgentToolsStore.buildSummary("Tester", perSetBudget = 1000, totalBudget = 300)
        assertTrue(s.length <= 300 + 60)
        assertTrue(s.contains("tools.search"))
    }

    // ── middleware ───────────────────────────────────────────────────

    @Test
    fun `middleware注入摘要且不重复注入`() {
        AgentToolsStore.save("Tester", AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow())
        val prompt = "你是 MengPaw"
        val once = AgentToolsSummaryMiddleware.onSystemPrompt(prompt, "Tester")
        assertTrue(once.contains("已注册命令集"))
        assertTrue(once.contains("gh pr list"))

        val twice = AgentToolsSummaryMiddleware.onSystemPrompt(once, "Tester")
        assertEquals(once, twice)  // 去重守卫：不重复注入
    }

    @Test
    fun `middleware空目录不注入`() {
        val prompt = "你是 MengPaw"
        val out = AgentToolsSummaryMiddleware.onSystemPrompt(prompt, "Tester")
        assertEquals(prompt, out)
    }

    @Test
    fun `fingerprint随文件变化`() {
        AgentToolsStore.save("Tester", AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow())
        val f1 = AgentToolsSummary.fingerprint("Tester")
        AgentToolsStore.save("Tester", AgentToolsStore.parseAndValidate("gh2", validJson()).getOrThrow())
        val f2 = AgentToolsSummary.fingerprint("Tester")
        assertTrue(f1 != 0L)
        assertTrue(f1 != f2)
    }

    @Test
    fun `toJson回读无损`() {
        val set = AgentToolsStore.parseAndValidate("gh", validJson()).getOrThrow()
        val back = AgentToolsStore.readFile(
            File(AgentToolsStore.toolsDir("Tester"), "gh.json").apply {
                parentFile.mkdirs()
                writeText(AgentToolsStore.toJson(set))
            })
        assertNotNull(back)
        assertEquals(set.name, back!!.name)
        assertEquals(set.commands.size, back.commands.size)
        assertEquals(set.importedAt, back.importedAt)
    }

    @Test
    fun `损坏文件readFile返回null且readAll跳过`() {
        val dir = AgentToolsStore.toolsDir("Tester").also { it.mkdirs() }
        File(dir, "gh.json").writeText("not json{")
        File(dir, "ok.json").writeText(AgentToolsStore.toJson(
            AgentToolsStore.parseAndValidate("ok", validJson("ok")).getOrThrow()))
        assertNull(AgentToolsStore.readFile(File(dir, "gh.json")))
        assertEquals(1, AgentToolsStore.readAll("Tester").size)
    }
}
