// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ToolIntentLog] 单元测试 — 覆盖: ① begin/finish 生命周期; ② 损坏行/损坏文件容错;
 * ③ 重放判重语义 (同一 toolCallId 幂等, 不产生重复 PENDING); ④ 凭据安全 (参数原文不落盘);
 * ⑤ pruneOlderThan 只删过期项且保留活跃 PENDING; ⑥ 并行 begin/finish 线程安全且无粘连行。
 * 全部断言基于状态/计数, 不依赖墙钟阈值。
 */
class ToolIntentLogTest {

    // ── ① begin → pending 可见 → finish → pending 清空 ──────────────────

    @Test
    fun `begin makes intent pending and finish clears it`() = runBlocking {
        val dir = tempDir("intent_basic")
        val log = ToolIntentLog(dir.absolutePath)
        val target = intent("s1", 3, "fs.write", "path=/a note=hi")

        log.begin(target)
        val pending = log.pending("s1")
        assertEquals(1, pending.size)
        assertEquals(target.toolCallId, pending.first().toolCallId)
        assertEquals(IntentState.PENDING, pending.first().state)

        log.finish("s1", target.toolCallId, ToolIntentLog.digestOf("写好了"), null)
        assertTrue(log.pending("s1").isEmpty())

        // 追加写: 一行 PENDING + 一行终态, 终态带 finishedAt 与结果摘要
        val records = readAll(dir, "s1")
        assertEquals(2, records.size)
        assertEquals(IntentState.DONE, records.last().state)
        assertEquals(ToolIntentLog.digestOf("写好了"), records.last().outcomeDigest)
        assertNotNull(records.last().finishedAt)
    }

    @Test
    fun `finish with error records failed state and reason`() = runBlocking {
        val dir = tempDir("intent_failed")
        val log = ToolIntentLog(dir.absolutePath)
        val target = intent("s1b", 1, "http.get", "url=https://example.com")

        log.begin(target)
        log.finish("s1b", target.toolCallId, null, "连接超时")

        assertTrue(log.pending("s1b").isEmpty())
        val last = readAll(dir, "s1b").last()
        assertEquals(IntentState.FAILED, last.state)
        assertEquals("连接超时", last.error)
    }

    @Test
    fun `pending is isolated per session`() = runBlocking {
        val dir = tempDir("intent_isolated")
        val log = ToolIntentLog(dir.absolutePath)
        log.begin(intent("sa", 1, "fs.write", "path=/a"))
        log.begin(intent("sb", 1, "fs.write", "path=/b"))

        assertEquals("sa", log.pending("sa").first().sessionId)
        assertEquals("sb", log.pending("sb").first().sessionId)
        assertTrue(log.pending("sc").isEmpty())
    }

    // ── ② 损坏行 / 损坏文件容错 ────────────────────────────────────────

    @Test
    fun `corrupted lines and non-json files are skipped without throwing`() = runBlocking {
        val dir = tempDir("intent_corrupt")
        val log = ToolIntentLog(dir.absolutePath)
        val good = intent("s2", 1, "fs.write", "path=/x")
        log.begin(good)

        // 崩溃留下的半行 + 一行非 JSON
        val f = logFile(dir, "s2")
        f.appendText("{\"sessionId\":\"s2\",\"step\":2,\"toolCall")
        f.appendText("\nnot-a-json-at-all\n")
        // 干扰文件 (后缀不符, 读侧与 prune 都必须忽略)
        File(dir, "noise.txt").writeText("{\"sessionId\":\"s2\"}")
        // 同名"文件"其实是目录 → 读侧必须走 isFile 判定而不是抛异常
        File(dir, ToolIntentLog.fileNameFor("s3")).mkdirs()

        // 新实例 (无任何内存索引) 直读磁盘: 只识别出完好那条, 且不抛
        val reader = ToolIntentLog(dir.absolutePath)
        val pending = reader.pending("s2")
        assertEquals(1, pending.size)
        assertEquals(good.toolCallId, pending.first().toolCallId)
        assertTrue(reader.pending("s3").isEmpty())

        // prune 同样容错, 并把损坏行压实掉 (只留活跃 PENDING 一行)
        assertEquals(0, reader.pruneOlderThan(nowMs = 10_000L, maxAgeMs = 1_000L))
        assertEquals(1, readAll(dir, "s2").size)
        assertEquals(1, reader.pending("s2").size)
    }

    // ── ③ 重放判重语义 (同一 toolCallId 幂等) ──────────────────────────

    @Test
    fun `second begin with same toolCallId keeps a single pending record`() = runBlocking {
        val dir = tempDir("intent_replay")
        val log = ToolIntentLog(dir.absolutePath)
        val first = intent("s4", 5, "msg.send", "to=u text=hi", startedAt = 1_000L)

        log.begin(first)
        // 重放: (会话, 步, 工具, 参数) 全同 → 同一 toolCallId
        log.begin(intent("s4", 5, "msg.send", "to=u text=hi", startedAt = 2_000L))

        val pending = log.pending("s4")
        assertEquals(1, pending.size)
        assertEquals(first.toolCallId, pending.first().toolCallId)
        assertEquals(IntentState.PENDING, pending.first().state)
        // 磁盘上也只有一条 — 保留首次 begin 的基线 (上次未完成的起始时刻)
        val records = readAll(dir, "s4")
        assertEquals(1, records.size)
        assertEquals(1_000L, records.first().startedAt)

        // 已有结论的 id 不会被重放"复活"为 PENDING
        log.finish("s4", first.toolCallId, null, null)
        log.begin(intent("s4", 5, "msg.send", "to=u text=hi"))
        assertTrue(log.pending("s4").isEmpty())
        assertEquals(IntentState.DONE, readAll(dir, "s4").last().state)
    }

    @Test
    fun `toolCallId is stable and reproducible from session step tool and args digest`() {
        val d1 = ToolIntentLog.digestOf("path=/a note=hi")
        assertEquals(d1, ToolIntentLog.digestOf("path=/a note=hi"))
        assertEquals(16, d1.length)

        val id = ToolIntentLog.toolCallIdOf("sX", 4, "fs.write", d1)
        assertEquals(ToolIntentLog.toolCallIdOf("sX", 4, "fs.write", d1), id)
        assertEquals("sX-4-fs.write-$d1", id)
        // 维度任一变化 → id 变化 (step / 工具 / 参数)
        assertTrue(id != ToolIntentLog.toolCallIdOf("sX", 5, "fs.write", d1))
        assertTrue(id != ToolIntentLog.toolCallIdOf("sX", 4, "fs.read", d1))
        assertTrue(id != ToolIntentLog.toolCallIdOf("sX", 4, "fs.write", ToolIntentLog.digestOf("path=/b")))
    }

    @Test
    fun `file name is sanitized against path traversal`() {
        val name = ToolIntentLog.fileNameFor("../etc/passwd")
        assertTrue(name.endsWith(ToolIntentLog.SUFFIX))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(".."))
        assertEquals(ToolIntentLog.fileNameFor("abcdef12"), "abcdef12.jsonl")
    }

    // ── ④ 凭据安全 ─────────────────────────────────────────────────────

    @Test
    fun `intent log never contains raw argument text or api key`() = runBlocking {
        val dir = tempDir("intent_secret")
        val log = ToolIntentLog(dir.absolutePath)
        // 假密钥 (非真实凭据), 长度满足 Sanitizer 的 sk- 脱敏模式
        val secret = "sk-abcdefghij0123456789ABCDEF"
        val args = "model=gpt url=https://api.example.com key=$secret"
        val commandLine = "http.get $args"
        val target = intent("s5", 7, "http.get", args)

        log.begin(target)
        // 与 executeActions 相同的落盘路径: 先按值剔除命令行/参数, 再交 finish 模式脱敏
        log.finish("s5", target.toolCallId, null,
            ToolIntentLog.scrubError("命令超时 (60s): $commandLine。请检查网络连接。", commandLine))

        val raw = logFile(dir, "s5").readText()
        assertFalse("落盘内容不得含原始参数文本", raw.contains(args))
        assertFalse("落盘内容不得含密钥", raw.contains(secret))
        assertFalse("落盘内容不得含参数任何片段", raw.contains("api.example.com"))
        assertTrue("参数只以摘要形式落盘", raw.contains(ToolIntentLog.digestOf(args)))
        assertTrue("内嵌命令行被替换为占位符", raw.contains("<命令>"))
        // 模式脱敏 (第二道防线) 兜住工具自行回显的凭据形态
        log.finish("s5", target.toolCallId, null, "调用失败: key=$secret 无效")
        assertTrue("错误文本经 Sanitizer 脱敏", logFile(dir, "s5").readText().contains("***REDACTED***"))
    }

    @Test
    fun `scrubError removes verbatim command line and args`() {
        val args = "to=u text=hello"
        val commandLine = "msg.send $args"
        val scrubbed = ToolIntentLog.scrubError("命令超时 (60s): $commandLine。请检查网络", commandLine)

        assertFalse(scrubbed.contains(args))
        assertFalse(scrubbed.contains(commandLine))
        assertTrue(scrubbed.contains("<命令>"))
        // 与命令行无关的错误文本原样保留
        assertEquals("连接超时", ToolIntentLog.scrubError("连接超时", commandLine))
    }

    // ── ⑤ pruneOlderThan ──────────────────────────────────────────────

    @Test
    fun `prune removes expired records but keeps active pending`() = runBlocking {
        val dir = tempDir("intent_prune")
        val now = 1_000_000L
        // 手工构造 (finish 用真实时钟, 造不出"很久以前完成"的记录)
        val staleDone = intent("s6", 1, "fs.write", "path=/old", startedAt = 1_000L)
            .copy(state = IntentState.DONE, finishedAt = 2_000L)
        val activePending = intent("s6", 2, "fs.write", "path=/pending", startedAt = 1_000L)
        val freshDone = intent("s6", 3, "fs.write", "path=/new", startedAt = 999_000L)
            .copy(state = IntentState.DONE, finishedAt = 999_500L)
        writeLines(logFile(dir, "s6"), listOf(staleDone, activePending, freshDone))

        val log = ToolIntentLog(dir.absolutePath)
        // maxAgeMs <= 0 不做清理 (防误传)
        assertEquals(0, log.pruneOlderThan(now, 0L))

        val pruned = log.pruneOlderThan(nowMs = now, maxAgeMs = 50_000L)
        assertEquals(1, pruned)

        val kept = readAll(dir, "s6").map { it.toolCallId }
        assertFalse("过期 DONE 应被删除", kept.contains(staleDone.toolCallId))
        assertTrue("未过期 DONE 应保留", kept.contains(freshDone.toolCallId))
        val pending = log.pending("s6")
        assertEquals(1, pending.size)
        assertEquals("活跃 PENDING 必须保留", activePending.toolCallId, pending.first().toolCallId)

        // 已清理的 id 不再被视为"已写过": 同一 id 可重新 begin (下次运行会写新记录)。
        // 注意传 PENDING 副本 —— begin 的契约是"声明即将执行", 生产调用方永远传 PENDING;
        // 传 DONE 副本会被原样落盘 (state=DONE), 于是 pending() 里当然找不到它。
        log.begin(staleDone.copy(state = IntentState.PENDING, finishedAt = null))
        assertEquals(
            IntentState.PENDING,
            log.pending("s6").first { it.toolCallId == staleDone.toolCallId }.state
        )
    }

    @Test
    fun `clear removes all records of the session`() = runBlocking {
        val dir = tempDir("intent_clear")
        val log = ToolIntentLog(dir.absolutePath)
        log.begin(intent("s7", 1, "fs.write", "path=/a"))
        log.clear("s7")

        assertTrue(log.pending("s7").isEmpty())
        assertFalse(logFile(dir, "s7").exists())
    }

    // ── ⑥ 并发安全 (executeActions 会并行执行一批工具) ──────────────────

    @Test
    fun `parallel begin and finish keep the log consistent`() = runBlocking {
        val dir = tempDir("intent_parallel")
        val log = ToolIntentLog(dir.absolutePath)
        val batch = (1..8).map { i -> intent("s8", 1, "tool.$i", "arg=$i") }

        batch.map { item ->
            async(Dispatchers.Default) {
                log.begin(item)
                log.finish(item.sessionId, item.toolCallId, ToolIntentLog.digestOf("out"), null)
            }
        }.awaitAll()

        assertTrue(log.pending("s8").isEmpty())
        // 无丢行、无半行、无两行粘连 (粘连会让解析出的条数变少)
        val records = readAll(dir, "s8")
        assertEquals(16, records.size)
        assertEquals(8, records.count { it.state == IntentState.PENDING })
        assertEquals(8, records.count { it.state == IntentState.DONE })
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), prefix + "_" + System.nanoTime()).apply { mkdirs() }

    private fun intent(
        sessionId: String,
        step: Int,
        toolName: String,
        args: String,
        startedAt: Long = 1_000L
    ): ToolIntent {
        val digest = ToolIntentLog.digestOf(args)
        return ToolIntent(
            sessionId = sessionId,
            step = step,
            toolCallId = ToolIntentLog.toolCallIdOf(sessionId, step, toolName, digest),
            toolName = toolName,
            argsDigest = digest,
            state = IntentState.PENDING,
            startedAt = startedAt
        )
    }

    private fun logFile(dir: File, sessionId: String): File =
        File(dir, ToolIntentLog.fileNameFor(sessionId))

    /** 直读落盘文件并逐行解析 (损坏行跳过) — 断言"磁盘上到底写了什么"。 */
    private fun readAll(dir: File, sessionId: String): List<ToolIntent> {
        val f = logFile(dir, sessionId)
        if (!f.isFile) return emptyList()
        val out = mutableListOf<ToolIntent>()
        for (line in f.readLines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            try {
                out.add(JSON.decodeFromString<ToolIntent>(t))
            } catch (_: Exception) {
                // 损坏行跳过 (与生产读侧同语义)
            }
        }
        return out
    }

    private fun writeLines(f: File, records: List<ToolIntent>) {
        f.parentFile?.mkdirs()
        f.writeText(records.joinToString("") { JSON.encodeToString(it) + "\n" }, Charsets.UTF_8)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
