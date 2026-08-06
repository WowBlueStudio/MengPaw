// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RootShell 安全门禁纯逻辑测试 (插件零测试补齐)。
 *
 * 只测拦截/分词/规范化/转义纯函数 — 绝不调用 execute / checkSu
 * (会真实执行 su 命令, 破坏环境)。
 *
 * 覆盖:
 * - BLOCKED_PATTERNS 正则黑名单命中
 * - checkRmCommand 绕过变体 (P1 修复回归): 拆旗标 / 根通配 / 引号包裹 / 路径拼接
 * - checkRmTarget 危险前缀
 * - normalizePath / tokenize / shellQuote
 */
class RootShellGuardTest {

    // ── BLOCKED_PATTERNS 正则黑名单 ─────────────────────────────

    @Test
    fun `BLOCKED_PATTERNS 命中灾难性命令`() {
        // 注: "rm -rf /" 与 "rm -rf /*" 不在此列 — 尾随 \b 在行尾 (非词字符后) 不匹配,
        // 裸根 rm 由 checkRmCommand 层拦截 (见 rm 绕过变体全部拦截)
        val catastrophic = listOf(
            "rm -rf /system",
            "rm -rf /boot",
            "rm -rf /system/app/evil",
            "dd if=/dev/zero of=/dev/sda",
            "dd if=/tmp/x.iso of=/dev/mmcblk0",
            "mkfs.ext4 /dev/sda1",
            "cat /dev/zero > /dev/sda",
            "rm -rf /data/data/com.mengpaw.shell && rm -rf /tmp"  // 自毁模式
        )
        catastrophic.forEach { cmd ->
            val hit = RootShell.BLOCKED_PATTERNS.any { it.containsMatchIn(cmd) }
            assertTrue("应拦截: $cmd", hit)
        }
    }

    @Test
    fun `BLOCKED_PATTERNS 不误伤正常命令`() {
        // 注意: 正则非锚定 — "echo rm -rf /" 之类文本命中词面 rm -rf / 也会被拦 (既有行为, 不作回归断言)
        val benign = listOf(
            "rm /sdcard/test.txt",
            "ls /system",
            "cat /etc/hosts",
            "dd if=/dev/zero of=/sdcard/zero.bin"   // 目标不是块设备
        )
        benign.forEach { cmd ->
            val hit = RootShell.BLOCKED_PATTERNS.any { it.containsMatchIn(cmd) }
            assertTrue("不应拦截: $cmd", !hit)
        }
    }

    // ── checkRmCommand: P1 绕过变体全部拦截 ─────────────────────

    @Test
    fun `rm 绕过变体全部拦截`() {
        val bypasses = listOf(
            "rm -r -f /",                  // 拆开递归与强制旗标
            "rm -rf /*",                   // 根通配
            "rm -rf \"/\"",                // 引号包裹
            "rm -rf /system/app",          // 系统分区 (前缀带尾斜杠才命中 — 裸 /system 仅正则层拦截)
            "rm -rf /etc/../boot",         // 路径拼接归一后命中 /boot
            "rm -rf /boot/../boot",        // 路径拼接 (归一后仍 /boot)
            "rm -rf /data/app/com.evil",   // 应用私有数据
            "rm -rf -- /system/app",       // 双横杠终止旗标解析
            "rm -f /sdcard/a.txt && rm -rf /boot",  // 命令链中的危险 rm
            "rm -rf ../boot",              // 相对路径拼接
            "/system/bin/rm -rf /"         // 全路径 rm 前缀
        )
        bypasses.forEach { cmd ->
            assertNotNull("应拦截: $cmd", RootShell.checkRmCommand(cmd))
        }
    }

    @Test
    fun `合法 rm 与读操作放行`() {
        assertNull(RootShell.checkRmCommand("rm /sdcard/test.txt"))          // 无递归旗标
        assertNull(RootShell.checkRmCommand("rm -rf /sdcard/test.txt"))      // 用户数据区不在危险前缀
        assertNull(RootShell.checkRmCommand("ls /system"))                   // 非 rm
        assertNull(RootShell.checkRmCommand("cat /etc/hosts"))
        assertNull(RootShell.checkRmCommand("touch /data/empty.txt"))        // 非 rm
        assertNull(RootShell.checkRmCommand("rmdir /sdcard/dir"))            // rmdir 非 rm -rf 语义
    }

    // ── checkRmTarget: 危险前缀 ─────────────────────────────────

    @Test
    fun `checkRmTarget 根与系统前缀拦截 用户数据放行`() {
        assertNotNull(RootShell.checkRmTarget("/"))
        assertNotNull(RootShell.checkRmTarget("/*"))
        assertNotNull(RootShell.checkRmTarget("/etc/passwd"))
        assertNotNull(RootShell.checkRmTarget("/data/data/com.mengpaw"))
        assertNotNull(RootShell.checkRmTarget("/boot"))
        assertNotNull(RootShell.checkRmTarget("/proc/self/mem"))
        assertNull(RootShell.checkRmTarget("/sdcard/DCIM"))
        assertNull(RootShell.checkRmTarget("/storage/emulated/0/test.txt"))
    }

    // ── normalizePath: 路径归一 ─────────────────────────────────

    @Test
    fun `normalizePath 压缩重复斜杠并展开点段`() {
        assertEquals("/boot", RootShell.normalizePath("/etc/../boot"))
        assertEquals("/a/b/c", RootShell.normalizePath("/a//b/./c"))
        assertEquals("/", RootShell.normalizePath("/"))
        assertEquals("/", RootShell.normalizePath("/../.."))
        assertEquals("/data/app/x", RootShell.normalizePath("/data/app/../app/x"))
        assertEquals("/sdcard/x", RootShell.normalizePath("sdcard/x"))
    }

    // ── tokenize: 引号与转义还原 ────────────────────────────────

    @Test
    fun `tokenize 还原引号内空格与反斜杠转义`() {
        assertEquals(listOf("echo", "hello world"), RootShell.tokenize("echo 'hello world'"))
        assertEquals(listOf("ls", "a b", "c"), RootShell.tokenize("""ls "a b" c"""))
        assertEquals(listOf("a b"), RootShell.tokenize("a\\ b"))
        assertEquals(listOf("rm", "-rf", "/x y"), RootShell.tokenize("rm -rf '/x y'"))
        assertEquals(emptyList<String>(), RootShell.tokenize("   "))
    }

    @Test
    fun `tokenize 处理混合引号嵌套`() {
        // 双引号内含单引号
        assertEquals(listOf("echo", "it's fine"), RootShell.tokenize("""echo "it's fine""""))
        // 单引号内含双引号
        assertEquals(listOf("echo", "say \"hi\""), RootShell.tokenize("""echo 'say "hi"'"""))
    }

    // ── shellQuote: 注入免疫 ────────────────────────────────────

    @Test
    fun `shellQuote 包裹参数且单引号转义后可无损还原`() {
        val cases = listOf(
            "plain",
            "x'; rm -rf /; '",
            "\$(id)",
            "`cat /etc/passwd`",
            "a && b || c",
            "hello 'world' \"again\""
        )
        cases.forEach { arg ->
            val quoted = RootShell.shellQuote(arg)
            assertTrue("必须以单引号开头: $quoted", quoted.startsWith("'"))
            assertTrue("必须以单引号结尾: $quoted", quoted.endsWith("'"))
            // 还原引号与转义后必须等于原参数 — 证明无内容丢失/变形
            val unquoted = quoted.removeSurrounding("'").replace("'\\''", "'")
            assertEquals("转义往返必须保真: $arg", arg, unquoted)
        }
    }

    @Test
    fun `shellQuote 注入载荷不出引号`() {
        // 转义后整串在单引号内 — 分号/命令替换/管道不可能被 shell 执行
        val q = RootShell.shellQuote("';rm -rf /;\$(id);`reboot`")
        assertEquals("''\\'';rm -rf /;$(id);`reboot`'", q)
        // 引号内部不存在裸分号分段 (除转义序列外)
        assertTrue(q.startsWith("''\\''"))
    }
}
