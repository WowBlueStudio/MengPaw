// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * FleetFileHandler 回归测试 (v0.36) —
 * 任意格式文件经 ACP 互传落 Fleet共享 目录: 消毒/落盘/sha 校验/大小上限。
 */
class FleetFileHandlerTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "fleet_file_test-${System.nanoTime()}")

    @Before
    fun init() {
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)
    }

    @After
    fun cleanup() {
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    private fun bytesOf(s: String): ByteArray = s.toByteArray()
    private fun sha256Hex(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    @Test
    fun 任意格式文件落盘Fleet共享目录() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetFileHandler()
        val bytes = bytesOf("APK 二进制内容测试")
        val msg = AcpMessage.fleetFile("peer", "to", "app-debug.apk",
            Base64.getEncoder().encodeToString(bytes), sha256Hex(bytes), bytes.size.toLong())
        val result = runBlocking { handler.handle(msg, server) }
        assertTrue(result?.success == true)
        val saved = File(com.mengpaw.kernel.DataPaths.FLEET_SHARE, "app-debug.apk")
        assertTrue("文件应落盘 Fleet共享", saved.exists())
        assertEquals("内容一致", String(bytes), saved.readText())
    }

    @Test
    fun 路径穿越文件名拒绝() {
        val handler = FleetFileHandler()
        assertNull(handler.sanitizeFleetFileName("../../etc/passwd"))
        assertNull(handler.sanitizeFleetFileName("/abs/path"))
        assertNull(handler.sanitizeFleetFileName("C:\\evil.exe"))
        assertNull(handler.sanitizeFleetFileName("a/b"))
        assertEquals("正常文件名保留", "release.apk", handler.sanitizeFleetFileName("release.apk"))
    }

    @Test
    fun sha校验不匹配拒绝() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetFileHandler()
        val bytes = bytesOf("hello")
        val msg = AcpMessage.fleetFile("peer", "to", "x.bin",
            Base64.getEncoder().encodeToString(bytes), "0000", bytes.size.toLong())
        val result = runBlocking { handler.handle(msg, server) }
        assertFalse(result?.success == true)
    }

    @Test
    fun 超过大小上限拒绝() {
        val server = AcpServer(AgentProfile(), sharedSecret = "test")
        val handler = FleetFileHandler()
        val msg = AcpMessage.fleetFile("peer", "to", "big.bin", "AA==", "x", FleetFileHandler.MAX_FILE_BYTES + 1)
        val result = runBlocking { handler.handle(msg, server) }
        assertFalse(result?.success == true)
    }
}
