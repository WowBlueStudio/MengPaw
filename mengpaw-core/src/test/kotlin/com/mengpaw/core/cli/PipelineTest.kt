package com.mengpaw.core.cli

import com.mengpaw.core.namespace.FsExecutor
import com.mengpaw.core.namespace.SelfExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PipelineTest {

    private fun createPipeline(): Pipeline {
        val registry = CommandRegistry()
        registry.registerNamespace("fs", FsExecutor.commands)
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

        // Write using a simple relative path (no backslash issues)
        val writeResult = pipeline.execute("fs.write \"$testFile\" \"HelloWorld\"", ctx)
        assertTrue("Write failed: ${writeResult.error}", writeResult.success)

        // Read back
        val readResult = pipeline.execute("fs.cat \"$testFile\"", ctx)
        assertTrue("Read failed: ${readResult.error}", readResult.success)
        assertEquals("HelloWorld", readResult.output)

        // Cleanup
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
}
