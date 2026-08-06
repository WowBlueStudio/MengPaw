// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.tavily

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * tavily.setup key 来源与脱敏单测 (P2-9):
 *  1. --from-file 从文件首行读取 (trim), 返回值/落盘均不含 key 明文;
 *  2. 内联 <key> 兼容旧用法, 成功消息只回显 key 长度;
 *  3. --from-clipboard 插件层无剪贴板能力, 明确报错并引导 --from-file。
 *
 * 审计可见内容说明: 审计日志由 kernel Pipeline 记录 (命令原文 + 脱敏后输出),
 * 插件层无法拦截命令文本 — 本测试覆盖插件可控部分: 输出零明文 + 存储零明文。
 */
class SetupKeySourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var plugin: TavilyPlugin

    @Before
    fun setUp() {
        // 独立临时 BASE, 避免污染真机数据目录
        DataPaths.initialize(tmp.root.absolutePath)
        plugin = TavilyPlugin()
    }

    private val ctx = ExecutionContext(sessionId = "test-session")

    private fun storedConfigText(): String =
        File(DataPaths.CONFIG, "tavily.json").readText(Charsets.UTF_8)

    @Test
    fun `from file reads first line trimmed and never echoes key`() {
        val keyFile = tmp.newFile("key.txt")
        keyFile.writeText("  tvly-secret-key-12345  \nsecond line 只取首行\n")

        val result = runBlocking { plugin.setup(listOf("--from-file", keyFile.absolutePath), ctx) }

        assertTrue("setup 应成功", result.success)
        assertFalse("输出不得含 key 原文", result.output.contains("tvly-secret-key-12345"))
        assertTrue("输出应回显 key 长度", result.output.contains("key 长度 21"))
        // 落盘必须是混淆形式, 不得有明文 (审计可见内容 = 输出, 存储 = 混淆)
        val stored = storedConfigText()
        assertFalse("存储不得含明文 key", stored.contains("tvly-secret-key-12345"))
        assertTrue("存储应带混淆前缀", stored.contains("\"obf:"))
    }

    @Test
    fun `inline key keeps working but output only shows length`() {
        val result = runBlocking { plugin.setup(listOf("tvly-inline-key-000"), ctx) }

        assertTrue("内联用法应兼容", result.success)
        assertFalse("输出不得含 key 原文", result.output.contains("tvly-inline-key-000"))
        assertTrue("输出应回显 key 长度", result.output.contains("key 长度 19"))
        assertFalse("存储不得含明文 key", storedConfigText().contains("tvly-inline-key-000"))
    }

    @Test
    fun `clipboard source is rejected with from-file guidance`() {
        val result = runBlocking { plugin.setup(listOf("--from-clipboard"), ctx) }

        assertFalse("--from-clipboard 应明确失败", result.success)
        assertNotNull(result.error)
        assertTrue("错误消息应引导 --from-file", result.error!!.contains("--from-file"))
        assertFalse("存储不得被写入", File(DataPaths.CONFIG, "tavily.json").exists())
    }

    @Test
    fun `from file with missing path arg fails with usage`() {
        val result = runBlocking { plugin.setup(listOf("--from-file"), ctx) }

        assertFalse(result.success)
        assertTrue(result.error!!.contains("--from-file <路径>"))
    }

    @Test
    fun `from file with nonexistent path fails clearly`() {
        val result = runBlocking { plugin.setup(listOf("--from-file", "no_such_file.txt"), ctx) }

        assertFalse(result.success)
        assertTrue(result.error!!.contains("不存在"))
    }

    @Test
    fun `from file with empty first line fails clearly`() {
        val keyFile = tmp.newFile("empty.txt")
        keyFile.writeText("   \n")

        val result = runBlocking { plugin.setup(listOf("--from-file", keyFile.absolutePath), ctx) }

        assertFalse(result.success)
        assertTrue(result.error!!.contains("首行为空"))
    }

    @Test
    fun `status message shows length not key windows`() {
        // 先写入一个 key, 再不带参查询状态
        runBlocking { plugin.setup(listOf("tvly-status-check-777"), ctx) }

        val result = runBlocking { plugin.setup(emptyList(), ctx) }

        assertTrue(result.success)
        assertFalse("状态消息不得含 key 原文", result.output.contains("tvly-status-check-777"))
        assertTrue("状态消息应回显长度", result.output.contains("key 长度 21"))
        // 旧实现的 take(4)...takeLast(4) 掩码窗口不得出现
        assertFalse("状态消息不得含 key 头窗口", result.output.contains("tvly"))
    }
}
