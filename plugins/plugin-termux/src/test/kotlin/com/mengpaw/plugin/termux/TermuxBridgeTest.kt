// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.termux

import com.mengpaw.kernel.security.CommandMonitor
import com.mengpaw.kernel.security.UserConfirmBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Termux 桥纯逻辑回归测试 (v0.36.3) — am 参数构造/脚本生成/安全扫描/结果解析。
 * 锁住的设计点: ① am --esa payload 不含逗号 (逗号会被 am 切分参数数组);
 * ② 内容走内核高危规则审查 (BLOCK/CONFIRM) 而元字符合法; ③ 结果标记解析。
 */
class TermuxBridgeTest {

    @Before
    fun setUp() {
        CommandMonitor.resetForTest()
    }

    @Test
    fun `am payload contains no comma`() {
        val payload = TermuxBridge.buildAmPayload("/sdcard/MengPaw/termux/run_1.sh", "/sdcard/MengPaw/termux/run_1.out")
        assertTrue("payload 不得含逗号: $payload", !payload.contains(','))
        assertTrue(payload.contains("proot-distro login ubuntu -- bash /sdcard/MengPaw/termux/run_1.sh"))
        assertTrue(payload.contains("> /sdcard/MengPaw/termux/run_1.out 2>&1"))
    }

    @Test
    fun `am args target termux RunCommandService with esa payload`() {
        val payload = TermuxBridge.buildAmPayload("/sdcard/MengPaw/termux/run_1.sh", "/sdcard/MengPaw/termux/run_1.out")
        val args = TermuxBridge.buildAmArgs(payload)
        assertTrue(args.contains("com.termux/com.termux.app.RunCommandService"))
        val esaIdx = args.indexOf("--esa")
        assertTrue(esaIdx >= 0)
        assertEquals("com.termux.RUN_COMMAND_ARGUMENTS", args[esaIdx + 1])
        assertEquals("-c,$payload", args[esaIdx + 2])
        assertTrue(args.contains("/data/data/com.termux/files/usr/bin/bash"))
    }

    @Test
    fun `python path for env and base`() {
        assertEquals("/root/miniconda3/bin/python", TermuxBridge.pythonForEnv("/root/miniconda3", null))
        assertEquals("/root/miniconda3/bin/python", TermuxBridge.pythonForEnv("/root/miniconda3", ""))
        assertEquals("/root/miniconda3/envs/py310/bin/python", TermuxBridge.pythonForEnv("/root/miniconda3", "py310"))
    }

    @Test
    fun `probe script reports conda envs python`() {
        val script = TermuxBridge.buildProbeScript()
        assertTrue(script.contains("WHOAMI="))
        assertTrue(script.contains("CONDA="))
        assertTrue(script.contains("ENVS="))
        assertTrue(script.contains("PYTHON="))
        assertTrue("探测脚本必须保留 shell 命令替换: " + script, script.contains("$(id -un)"))
        assertTrue("探测脚本必须保留变量: " + script, script.contains("\$CONDA"))
    }

    @Test
    fun `python script invokes python file and emits rc plus marker`() {
        val script = TermuxBridge.buildPythonScript("/sdcard/MengPaw/termux/run_1.py", "__MENGPAW_DONE_t1")
        assertTrue(script.contains("<PYTHON> /sdcard/MengPaw/termux/run_1.py"))
        assertTrue("脚本必须捕获退出码: " + script, script.contains("rc=\$?"))
        assertTrue(script.contains("__MENGPAW_RC__\$rc"))
        assertTrue(script.contains("__MENGPAW_DONE_t1"))
    }

    @Test
    fun `ubuntu script sources conda and activates env before command`() {
        val script = TermuxBridge.buildUbuntuScript(
            condaDir = "/root/miniconda3", env = "py310",
            command = "pip list", marker = "__MENGPAW_DONE_t2"
        )
        assertTrue(script.contains("CONDA=\"/root/miniconda3\""))
        assertTrue(script.contains("source \"\$CONDA/etc/profile.d/conda.sh\""))
        assertTrue(script.contains("conda activate py310"))
        assertTrue(script.contains("pip list"))
        assertTrue(script.contains("__MENGPAW_DONE_t2"))
    }

    @Test
    fun `run result parses rc and strips markers`() {
        val out = "hello\nworld\n__MENGPAW_RC__3\n__MENGPAW_DONE_t1\n"
        val (rc, body) = TermuxBridge.extractRunResult(out, "__MENGPAW_DONE_t1")
        assertEquals(3, rc)
        assertEquals("hello\nworld", body)

        val (rc0, body0) = TermuxBridge.extractRunResult("ok\n__MENGPAW_DONE_t2", "__MENGPAW_DONE_t2")
        assertEquals(0, rc0)
        assertEquals("ok", body0)
    }

    @Test
    fun `content scan blocks high risk but allows shell syntax`() = runBlocking {
        assertNotNull(TermuxBridge.checkContent("rm -rf /"))
        assertNotNull(TermuxBridge.checkContent("echo x > /etc/hosts"))
        assertNull(TermuxBridge.checkContent("import sys; print(sys.version_info)"))
        assertNull(TermuxBridge.checkContent("conda activate py && python3 -c \"print(1)\""))
    }

    @Test
    fun `content scan confirm rule with user consent`() = runBlocking {
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, true)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertNull("用户同意后 rm 可放行", TermuxBridge.checkContent("rm /sdcard/tmp.txt"))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    @Test
    fun `am error hints actionable`() {
        assertTrue(TermuxBridge.hintForAmError("Error: Not found; no service started").contains("未安装"))
        assertTrue(TermuxBridge.hintForAmError("SecurityException: Not allowed to start service").contains("allow-external-apps"))
        assertTrue(TermuxBridge.hintForAmError("Background service start not allowed").contains("后台启动限制"))
    }
}
