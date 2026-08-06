// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.namespace.SelfExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class PipelineTest {

    @Before
    fun initPaths() {
        // P2-12(自检报告): Pipeline 事件落盘指向临时目录 — 避免开发机根目录生成 /sdcard
        com.mengpaw.kernel.DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_pipeline_test")
        com.mengpaw.kernel.Telemetry.eventsFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "events.jsonl")
    }

    private fun createPipeline(): Pipeline {
        val registry = CommandRegistry()
        // Mock fs commands (replaces deleted FsExecutor — fs is now a plugin)
        registry.register("fs.cat") { args, ctx ->
            val path = args.firstOrNull() ?: return@register ExecutionResult.fail("Usage: fs cat <path>")
            val file = File(path)
            if (!file.exists()) ExecutionResult.fail("File not found: $path", errorCode = "ERR_NOT_FOUND")
            else ExecutionResult.ok(file.readText())
        }
        registry.register("fs.write") { args, ctx ->
            if (args.size < 2) return@register ExecutionResult.fail("Usage: fs write <path> <content>")
            val file = File(args[0])
            file.parentFile?.mkdirs()
            file.writeText(args.drop(1).joinToString(" "))
            ExecutionResult.ok("Written")
        }
        registry.registerNamespace("self", SelfExecutor.commands)
        return Pipeline(registry = registry)
    }

    @Test
    fun `execute valid command`() = runTest {
        val pipeline = createPipeline()
        val ctx = ExecutionContext(sessionId = "test")
        val result = pipeline.execute("self.status", ctx)
        assertTrue(result.success)
        assertTrue(result.output.contains("Session: test"))
    }

    // ── Result cache tests ─────────────────────────────────────────────

    private fun cachedPipeline(ttl: Long = 10_000): Pair<Pipeline, java.util.concurrent.atomic.AtomicInteger> {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val registry = CommandRegistry()
        registry.register("self.status") { _, _ ->
            calls.incrementAndGet()
            ExecutionResult.ok("status-${calls.get()}")
        }
        return Pipeline(registry = registry, resultCache = CommandResultCache(ttlMillis = ttl)) to calls
    }

    @Test
    fun `cached read command executes handler once`() = runTest {
        val (pipeline, calls) = cachedPipeline()
        val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
        val r1 = pipeline.execute("self.status", ctx)
        val r2 = pipeline.execute("self.status", ctx)
        assertTrue(r1.success && r2.success)
        assertEquals("同参重复调用应命中缓存，handler 只执行 1 次", 1, calls.get())
        assertTrue("两次结果应一致", r1.output == r2.output)
    }

    @Test
    fun `cache key isolates by session`() = runTest {
        val (pipeline, calls) = cachedPipeline()
        val ctxA = ExecutionContext(sessionId = "sessA", agentName = "MengPaw")
        val ctxB = ExecutionContext(sessionId = "sessB", agentName = "MengPaw")
        pipeline.execute("self.status", ctxA)
        pipeline.execute("self.status", ctxB)
        assertEquals("不同会话不应共享缓存", 2, calls.get())
    }

    @Test
    fun `cache expires after ttl`() = runTest {
        val (pipeline, calls) = cachedPipeline(ttl = 50)
        val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
        pipeline.execute("self.status", ctx)
        Thread.sleep(120)
        pipeline.execute("self.status", ctx)
        assertEquals("TTL 过期后应重新执行", 2, calls.get())
    }

    @Test
    fun `non-whitelisted command never cached`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val registry = CommandRegistry()
        registry.register("fs.write") { _, _ -> calls.incrementAndGet(); ExecutionResult.ok("written") }
        val pipeline = Pipeline(registry = registry, resultCache = CommandResultCache())
        val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
        pipeline.execute("fs.write a b", ctx)
        pipeline.execute("fs.write a b", ctx)
        assertEquals("写命令不在白名单，不应缓存", 2, calls.get())
    }

    @Test
    fun `write command invalidates cache`() = runTest {
        // P1 回归锚点: "写入→立即读" 不得命中写前旧快照
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val registry = CommandRegistry()
        registry.register("self.status") { _, _ ->
            calls.incrementAndGet()
            ExecutionResult.ok("status-${calls.get()}")
        }
        registry.register("fs.write") { args, _ ->
            ExecutionResult.ok("written: ${args.firstOrNull()}")
        }
        val pipeline = Pipeline(registry = registry, resultCache = CommandResultCache())
        val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
        pipeline.execute("self.status", ctx)   // 读 → 缓存 status-1
        pipeline.execute("self.status", ctx)   // 命中缓存（calls 仍 1）
        assertEquals(1, calls.get())
        pipeline.execute("fs.write /a b", ctx)  // 写命令成功 → 清缓存
        pipeline.execute("self.status", ctx)   // 缓存已失效 → 重新执行
        assertEquals("写命令后读不应命中旧缓存", 2, calls.get())
    }

    @Test
    fun `cache evicts oldest beyond maxEntries`() = runTest {
        // 直接单测缓存（不经 Pipeline — maxEntries 淘汰路径）
        val cache = CommandResultCache(ttlMillis = 10_000, maxEntries = 3)
        repeat(4) { i -> cache.put("k$i", ExecutionResult.ok("v$i")) }
        assertNull("最早条目应被淘汰", cache.get("k0"))
        assertNotNull("最新条目应保留", cache.get("k3"))
    }

    @Test
    fun `oversized output not cached`() = runTest {
        val cache = CommandResultCache(ttlMillis = 10_000)
        cache.put("big", ExecutionResult.ok("x".repeat(70 * 1024)))  // 70KB > 64KB 上限
        assertNull("超大输出不应入缓存", cache.get("big"))
    }

    @Test
    fun `cache hit still produces audit entry`() = runTest {
        val (pipeline, calls) = cachedPipeline()
        val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
        pipeline.execute("self.status", ctx)
        pipeline.execute("self.status", ctx)
        val audit = pipeline.getSessionAudit("test")
        assertEquals("缓存命中也要留审计轨迹（2 次执行 2 条审计）", 2, audit.size)
    }

    @Test
    fun `execute empty command`() = runTest {
        val pipeline = createPipeline()
        val ctx = ExecutionContext(sessionId = "test")
        val result = pipeline.execute("", ctx)
        assertFalse(result.success)
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `execute whitespace-only command`() = runTest {
        val pipeline = createPipeline()
        val ctx = ExecutionContext(sessionId = "test")
        val result = pipeline.execute("   ", ctx)
        assertFalse(result.success)
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `execute unknown command`() = runTest {
        val pipeline = createPipeline()
        val ctx = ExecutionContext(sessionId = "test")
        val result = pipeline.execute("unknown.cmd", ctx)
        assertFalse(result.success)
        assertTrue(result.error?.contains("Unknown command") == true)
        assertEquals(ErrorCodes.ERR_NOT_FOUND, result.errorCode)
    }

    @Test
    fun `execute blocked command`() = runTest {
        val pipeline = createPipeline()
        val ctx = ExecutionContext(sessionId = "test")
        val result = pipeline.execute("proc.exec rm -rf /", ctx)
        assertFalse(result.success)
        assertTrue(result.error?.contains("blocked by security") == true)
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, result.errorCode)
    }

    @Test
    fun `fs write and read roundtrip`() = runTest {
        val pipeline = createPipeline()
        val tmpDir = (System.getProperty("java.io.tmpdir") ?: "/tmp").replace('\\', '/')
        val testFile = "$tmpDir/mengpaw_test_${System.currentTimeMillis()}.txt"
        val ctx = ExecutionContext(sessionId = "test", workDir = tmpDir)

        val writeResult = pipeline.execute("fs.write \"$testFile\" \"HelloWorld\"", ctx)
        assertTrue("Write failed: ${writeResult.error}", writeResult.success)

        val readResult = pipeline.execute("fs.cat \"$testFile\"", ctx)
        assertTrue("Read failed: ${readResult.error}", readResult.success)
        assertEquals("HelloWorld", readResult.output)

        File(testFile).delete()
    }

    @Test
    fun `successful result has no errorCode`() = runTest {
        val pipeline = createPipeline()
        val ctx = ExecutionContext(sessionId = "test")
        val result = pipeline.execute("self.version", ctx)
        assertTrue(result.success)
        assertNull(result.errorCode)
    }

    // ── P0-3 参数签名预校验 (自检报告: 参数无 schema, 错误反馈闭环弱) ─────

    @Test
    fun `signature rejects missing required args with unified format`() = runTest {
        val registry = CommandRegistry()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        registry.register("agent.read", CommandSignature("agent.read <路径>", 1)) { args, _ ->
            calls.incrementAndGet(); ExecutionResult.ok("ok")
        }
        val pipeline = Pipeline(registry = registry)
        val ctx = ExecutionContext(sessionId = "test", agentName = "MengPaw")
        val result = pipeline.execute("agent.read", ctx)
        assertFalse("必选参数不足应被框架层拦截", result.success)
        assertTrue("错误须含期望用法", result.error?.contains("期望用法「agent.read <路径>」") == true)
        assertTrue("错误须含收到参数数", result.error?.contains("收到 0 个参数") == true)
        assertEquals("拦截在 handler 之前", 0, calls.get())
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `signature passes when args sufficient`() = runTest {
        val registry = CommandRegistry()
        registry.register("agent.read", CommandSignature("agent.read <路径>", 1)) { _, _ -> ExecutionResult.ok("ok") }
        val pipeline = Pipeline(registry = registry)
        val result = pipeline.execute("agent.read profile.md", ExecutionContext(sessionId = "test"))
        assertTrue("参数足够应放行执行", result.success)
    }

    @Test
    fun `signature does not block multi-arg command`() = runTest {
        // 位置参数下限校验: agent.write <路径> <内容> 需 2 参, 内容含空格经 tokenize 拆多段不误拦
        val registry = CommandRegistry()
        registry.register("agent.write", CommandSignature("agent.write <路径> <内容>", 2)) { _, _ -> ExecutionResult.ok("ok") }
        val pipeline = Pipeline(registry = registry)
        val result = pipeline.execute("agent.write profile.md 记住 这个 重点", ExecutionContext(sessionId = "test"))
        assertTrue("参数多于下限应放行", result.success)
    }

    @Test
    fun `signature never blocks unsigned command`() = runTest {
        val registry = CommandRegistry()
        registry.register("self.search") { _, _ -> ExecutionResult.ok("stats") }
        val pipeline = Pipeline(registry = registry)
        val result = pipeline.execute("self.search", ExecutionContext(sessionId = "test"))
        assertTrue("无签名命令 (self.search 无参=统计) 不得被拦", result.success)
    }
}
