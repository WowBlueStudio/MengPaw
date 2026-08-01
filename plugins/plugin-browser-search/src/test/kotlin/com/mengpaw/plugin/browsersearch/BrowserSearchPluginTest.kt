// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.browsersearch

import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * browser-search 管道命令测试。
 * 每测试独立临时目录 + @Before 重置 DataPaths 保证隔离。
 * 注: fetch 的 SSRF 防护拦截回环地址, 成功路径用本地文件覆盖 (loadSource 本地分支)。
 */
class BrowserSearchPluginTest {

    private val plugin = BrowserSearchPlugin()
    private val ctx = ExecutionContext(sessionId = "test-session", agentName = "search-test")

    private val fixtureHtml = """
        <html><head><title>测试文章</title></head><body>
        <article>
        <h1>测试文章标题</h1>
        <nav>导航链接</nav>
        <p>第一段正文, 包含<a href="https://example.com/ref">引用链接</a>。</p>
        <script>var noise = 1;</script>
        <ul><li>要点一</li><li>要点二</li></ul>
        </article></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-search-test-${System.currentTimeMillis()}")
        tmp.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmp.absolutePath)
    }

    private suspend fun run(cmd: String, vararg args: String) =
        plugin.commands[cmd]!!(args.toList(), ctx)

    private fun fixtureFile(): File {
        val rawDir = File(com.mengpaw.kernel.DataPaths.SEARCH_OUTPUTS, "raw").apply { mkdirs() }
        return File(rawDir, "fixture.html").also { it.writeText(fixtureHtml) }
    }

    @Test
    fun `md rejects loopback addresses`() = runTest {
        val r = run("md", "http://127.0.0.1:8080/article")
        assertFalse("回环地址应被 SSRF 拦截", r.success)
        assertTrue((r.error ?: "").contains("Blocked internal address"))
    }

    @Test
    fun `md rejects non-http schemes`() = runTest {
        val r = run("md", "file:///etc/passwd")
        assertFalse("非 http/https 应被拦截", r.success)
    }

    @Test
    fun `md requires source argument`() = runTest {
        val r = run("md")
        assertFalse("缺来源应报用法错误", r.success)
        assertTrue((r.error ?: "").contains("用法"))
    }

    @Test
    fun `clean extracts main text without noise`() = runTest {
        val f = fixtureFile()
        val r = run("clean", f.absolutePath)
        assertTrue(r.success)
        assertTrue(r.output.contains("正文提取"))
        assertTrue(r.output.contains("第一段正文"))
        assertFalse("nav 应被剥离", r.output.contains("导航链接"))
        assertFalse("script 应被剥离", r.output.contains("var noise"))
        assertTrue("标题保留为 h1", r.output.contains("测试文章标题"))
    }

    @Test
    fun `md generates markdown file starting with title`() = runTest {
        val f = fixtureFile()
        val r = run("md", f.absolutePath, "--name", "article_test")
        assertTrue(r.success)
        val pathLine = r.output.lines().first { it.startsWith("- 文件:") }
        val file = File(pathLine.removePrefix("- 文件:").trim())
        assertTrue("md 文件应生成", file.exists())
        val content = file.readText()
        assertTrue(content.startsWith("# 测试文章"))
        assertTrue(content.contains("第一段正文"))
        assertTrue(content.contains("[引用链接](https://example.com/ref)"))
        assertTrue(content.contains("- 要点一"))
        assertFalse(content.contains("var noise"))
    }

    @Test
    fun `md refuses paths outside search outputs`() = runTest {
        val outside = File(System.getProperty("java.io.tmpdir"), "outside-${System.currentTimeMillis()}.html")
        outside.writeText("<html><body><p>外部文件</p></body></html>")
        val r = run("md", outside.absolutePath)
        assertFalse("SEARCH_OUTPUTS 外路径应被拒绝", r.success)
        outside.delete()
    }

    @Test
    fun `outputs lists generated files`() = runTest {
        val f = fixtureFile()
        run("md", f.absolutePath, "--name", "outputs_test")
        val r = run("outputs", "--all")
        assertTrue(r.success)
        assertTrue(r.output.contains("outputs_test"))
        assertTrue(r.output.contains("搜索输出"))
    }

    @Test
    fun `clear removes md files and keeps directory`() = runTest {
        val f = fixtureFile()
        run("md", f.absolutePath, "--name", "clear_test")
        val r = run("clear", "--all")
        assertTrue(r.success)
        assertTrue(r.output.startsWith("已清理"))
        val mdFiles = File(com.mengpaw.kernel.DataPaths.SEARCH_OUTPUTS)
            .listFiles { file -> file.extension == "md" }.orEmpty()
        assertEquals("md 文件应被清空", 0, mdFiles.size)
        assertTrue("输出目录应保留", File(com.mengpaw.kernel.DataPaths.SEARCH_OUTPUTS).exists())
    }
}
