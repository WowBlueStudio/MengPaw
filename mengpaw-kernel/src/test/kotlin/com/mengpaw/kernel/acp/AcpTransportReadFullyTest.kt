// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.Reader

/**
 * AcpTransport.readFully 回归测试 (v0.35.5) —
 * 单次 read() 不保证返回全部字节: WiFi 分片下真实配对请求 (~250B) 曾被截断,
 * 平板返回 400 "Invalid ACP message", 用户点击"添加"提示发送失败。
 * 用强制小分片的 Reader 模拟网络分片, 锁死"循环读满"行为。
 */
class AcpTransportReadFullyTest {

    /** 每次最多返回 [chunk] 字节的 Reader — 模拟 TCP 分片/部分读。 */
    private class ChunkedReader(data: CharArray, private val chunk: Int) : Reader() {
        private val buf = data
        private var pos = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (pos >= buf.size) return -1
            val n = minOf(chunk, len, buf.size - pos)
            System.arraycopy(buf, pos, cbuf, off, n)
            pos += n
            return n
        }

        override fun close() {}
    }

    private fun jsonBody() = """{"from":"mengpaw-abc-def","to":"*","type":"FRAMEWORK_PAIR_REQUEST","payload":"{\"requestId\":\"req123\",\"fingerprint\":\"mengpaw|aa:bb:cc:dd:ee:ff\",\"displayName\":\"小檬的平板\",\"address\":\"192.168.2.34\",\"port\":9876}","ttl":10,"requestId":"req123"}"""

    @Test
    fun 分片读取读满完整body() {
        val full = jsonBody()
        // 每次只给 16 字节 — 单次 read 读不满, 必须循环
        val reader = BufferedReader(ChunkedReader(full.toCharArray(), 16))
        assertEquals("分片后必须还原完整消息", full, readFully(reader, full.length))
    }

    @Test
    fun 小body一次读完() {
        val small = """{"from":"a","to":"*","type":"FRAMEWORK_PAIR_REQUEST","payload":"","ttl":10}"""
        val reader = BufferedReader(ChunkedReader(small.toCharArray(), 1024))
        assertEquals(small, readFully(reader, small.length))
    }

    @Test
    fun 空body返回空字符串() {
        assertEquals("", readFully(BufferedReader(ChunkedReader(charArrayOf(), 8)), 0))
    }

    @Test
    fun 连接提前关闭返回已读部分() {
        val partial = "abcdefgh"
        val reader = BufferedReader(ChunkedReader(partial.toCharArray(), 3))
        // 声明 10 字节但连接只给了 8 字节 (EOF) — 返回已读部分, 不抛异常
        assertEquals("abcdefgh", readFully(reader, 10))
    }
}
