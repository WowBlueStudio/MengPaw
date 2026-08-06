// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TwinAcpHandler.sanitizeRelPath 路径消毒测试 (插件零测试补齐 — P0 穿越修复回归)。
 *
 * 对端可控的 relPath 必须拒绝: 空白 / 绝对路径 / `..` 段 (含反斜杠变体) / Windows 盘符;
 * 合法相对路径原样放行。
 */
class TwinSanitizeRelPathTest {

    private lateinit var tempBase: File
    private lateinit var handler: TwinAcpHandler

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-twin-sanitize-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        // TwinAcpHandler 构造仅依赖 TwinSyncEngine (JVM 纯构造, 传空 supplier 不启动任何循环)
        handler = TwinAcpHandler(
            TwinSyncEngine(serverSupplier = { null }, transportSupplier = { null },
                agentName = "agent", deviceId = "dev-1", deviceName = "测试机")
        )
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    @Test
    fun `空白路径拒绝`() {
        assertNull(handler.sanitizeRelPath(""))
        assertNull(handler.sanitizeRelPath("   "))
        assertNull(handler.sanitizeRelPath("\t\n"))
    }

    @Test
    fun `绝对路径拒绝`() {
        assertNull(handler.sanitizeRelPath("/etc/passwd"))
        assertNull(handler.sanitizeRelPath("/data/data/com.mengpaw"))
        assertNull(handler.sanitizeRelPath("\\windows\\system32"))  // 反斜杠绝对路径
        assertNull(handler.sanitizeRelPath("/"))
    }

    @Test
    fun `含点点段拒绝 正斜杠与反斜杠双保险`() {
        assertNull(handler.sanitizeRelPath("../secret.md"))
        assertNull(handler.sanitizeRelPath("a/b/../../etc/passwd"))
        assertNull(handler.sanitizeRelPath("..\\secret.md"))
        assertNull(handler.sanitizeRelPath("memory\\..\\..\\secret.md"))
        assertNull(handler.sanitizeRelPath("a/../b/.."))
    }

    @Test
    fun `含冒号拒绝 Windows盘符与URL scheme`() {
        assertNull(handler.sanitizeRelPath("C:/windows/system32"))
        assertNull(handler.sanitizeRelPath("c:\\boot.ini"))
        assertNull(handler.sanitizeRelPath("file:///etc/passwd"))
    }

    @Test
    fun `合法相对路径放行且原样返回`() {
        assertEquals("memory/memory.md", handler.sanitizeRelPath("memory/memory.md"))
        assertEquals("soul.md", handler.sanitizeRelPath("soul.md"))
        assertEquals("记忆/存档.md", handler.sanitizeRelPath("记忆/存档.md"))
        assertEquals("a b/c.txt", handler.sanitizeRelPath("a b/c.txt"))  // 空格合法 (相对段)
        assertEquals("trumanshow.md", handler.sanitizeRelPath("trumanshow.md"))
    }

    @Test
    fun `单段点号放行且不误伤点开头的文件`() {
        // "." 单段不是 "..", 路径消毒不拒绝 (文件系统层会规范化)
        assertEquals(".hidden.md", handler.sanitizeRelPath(".hidden.md"))
        assertNull(handler.sanitizeRelPath("./../x.md"))
    }

    @Test
    fun `消毒结果与原串同一引用 (无复制无改写)`() {
        val input = "memory/memory.md"
        assertSame(input, handler.sanitizeRelPath(input))
    }
}
