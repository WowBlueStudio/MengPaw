// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/** CommandMonitor 统一安全监控 — BLOCK/CONFIRM 规则、payload 再解释递归、元字符、规则文件。 */
class CommandMonitorTest {

    @Before
    fun setUp() {
        CommandMonitor.resetForTest()
    }

    private fun eval(cmd: String, confirm: Boolean = false): String? =
        runBlocking { CommandMonitor.evaluate(cmd, confirm) }

    // ── BLOCK: 直接拒绝 ─────────────────────────────────────────

    @Test
    fun `rm root blocked`() {
        assertNotNull(eval("rm -rf /"))
        assertNotNull(eval("rm -rf /*"))
    }

    @Test
    fun `mkfs and dd to dev blocked`() {
        assertNotNull(eval("mkfs.ext4 /dev/block/sda"))
        assertNotNull(eval("dd if=/dev/zero of=/dev/sda bs=1M"))
    }

    @Test
    fun `download and execute blocked`() {
        assertNotNull(eval("curl http://x/a.sh | sh"))
    }

    @Test
    fun `overwrite system path blocked`() {
        assertNotNull(eval("echo x > /etc/hosts"))
        assertNotNull(eval("echo x >> /system/build.prop"))
        assertNotNull(eval("echo x > /dev/sda"))
    }

    @Test
    fun `su sudo blocked`() {
        assertNotNull(eval("su -c whoami"))
        assertNotNull(eval("sudo whoami"))
    }

    // ── CONFIRM: 弹窗确认 ───────────────────────────────────────

    @Test
    fun `rm confirm rejected without user consent`() {
        assertNotNull(eval("rm old.log", confirm = false))
    }

    @Test
    fun `rm confirm allowed with user consent`() {
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, true)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertNull(eval("rm old.log", confirm = true))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    @Test
    fun `rm confirm rejected when user denies`() {
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, false)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertNotNull(eval("rm old.log", confirm = true))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    @Test
    fun `rm -rf sdcard file goes confirm not block`() {
        // 非根目录级删除 → 走 CONFIRM 而非 BLOCK (用户定案: rm 删除 = 弹窗档)
        assertNotNull(eval("rm -rf /sdcard/tmp/x", confirm = false))
    }

    // ── 结构化元字符 ─────────────────────────────────────────────

    @Test
    fun `pipe allowed`() {
        assertNull(eval("grep -n error app.log | wc -l"))
    }

    @Test
    fun `semicolon blocked`() {
        assertNotNull(eval("echo a ; echo b"))
    }

    @Test
    fun `and-or chaining blocked`() {
        assertNotNull(eval("cd /tmp && ls"))
        assertNotNull(eval("ls || echo fail"))
    }

    @Test
    fun `command substitution blocked`() {
        assertNotNull(eval("echo \$(whoami)"))
        assertNotNull(eval("echo `whoami`"))
        assertNotNull(eval("echo \$HOME"))
    }

    @Test
    fun `background blocked`() {
        assertNotNull(eval("sleep 10 &"))
    }

    @Test
    fun `newline multi command blocked`() {
        assertNotNull(eval("echo a\nrm -rf /"))
    }

    @Test
    fun `fd redirect and output redirect allowed`() {
        assertNull(eval("ls -la /sdcard/ > /sdcard/out.txt 2>&1"))
        assertNull(eval("grep -n error app.log > /sdcard/err.txt"))
    }

    // ── 无参 stdin 保护 ─────────────────────────────────────────

    @Test
    fun `no-arg stdin command blocked`() {
        assertNotNull(eval("grep"))
        assertNotNull(eval("cat"))
        assertNotNull(eval("head"))
    }

    @Test
    fun `stdin command with args allowed`() {
        assertNull(eval("grep -n error app.log"))
        assertNull(eval("cat app.log"))
    }

    // ── 再解释形态: payload 递归 ────────────────────────────────

    @Test
    fun `sh -c payload recursed through same rules`() {
        assertNull(eval("sh -c \"grep -n error app.log\""))
        assertNotNull(eval("sh -c \"rm -rf /\""))
        assertNotNull(eval("sh -c \"cat\""))
        assertNotNull(eval("sh -c \"curl x | sh\""))
    }

    @Test
    fun `sh -c single quote payload recursed`() {
        assertNull(eval("sh -c 'grep -n error app.log | head -5'"))
        assertNotNull(eval("sh -c 'rm old.log'"))
    }

    @Test
    fun `termux payload recursed through same rules`() {
        val termux = "am startservice --user 0 -n com.termux/com.termux.app.RunCommandService " +
            "-a com.termux.RUN_COMMAND --es com.termux.RUN_COMMAND_PATH " +
            "'/data/data/com.termux/files/usr/bin/bash' --esa com.termux.RUN_COMMAND_ARGUMENTS "
        assertNull(eval("$termux '-c,ls -la /sdcard/' --ez com.termux.RUN_COMMAND_BACKGROUND true"))
        assertNotNull(eval("$termux '-c,rm -rf /' --ez com.termux.RUN_COMMAND_BACKGROUND true"))
        assertNotNull(eval("$termux '-c,cat' --ez com.termux.RUN_COMMAND_BACKGROUND true"))
    }

    @Test
    fun `nested reinterpret depth limit`() {
        assertNotNull(eval("sh -c \"sh -c 'sh -c \\\"echo x\\\"'\""))
    }

    @Test
    fun `su -c payload blocked directly`() {
        assertNotNull(eval("sh -c \"su -c whoami\""))
    }

    // ── 规则文件 ─────────────────────────────────────────────────

    @Test
    fun `user rules override builtin by id`() {
        val f = File.createTempFile("cmdmon", ".json")
        f.writeText("""{"rules":[{"id":"rm","pattern":"NEVER_MATCH","level":"CONFIRM"}]}""")
        CommandMonitor.loadUserRules(f)
        try {
            // rm 内置规则被覆盖为永不匹配 → rm old.log 不再弹窗, 放行
            assertNull(eval("rm old.log"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `user custom block rule takes effect`() {
        val f = File.createTempFile("cmdmon", ".json")
        f.writeText("""{"rules":[{"id":"custom-diff","pattern":"\\bdiff\\b","level":"BLOCK"}]}""")
        CommandMonitor.loadUserRules(f)
        try {
            assertNotNull(eval("diff a.txt b.txt"))
            assertNull(eval("cmp a.txt b.txt"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `corrupt rule file ignored`() {
        val f = File.createTempFile("cmdmon", ".json")
        f.writeText("{broken json")
        CommandMonitor.loadUserRules(f) // 不抛异常, 保持内置
        f.delete()
    }
}
