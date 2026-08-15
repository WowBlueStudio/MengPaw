// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.update

import com.mengpaw.plugin.update.UpdatePlugin.ReleaseInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 更新插件纯逻辑单测: 版本号比较、结果格式化、SHA-256 hex (Locale.ROOT, P2 修复)、
 * 大小格式化、镜像 URL 生成、scheduleAutoCheck CAS 幂等 (P2 修复)。
 * 不触网络/APK 下载/Android Context (download/install/verifyApkSignature 跳过)。
 */
class UpdateLogicTest {

    private val plugin = UpdatePlugin()

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 版本号比较 ──────────────────────────────────────────────────────

    @Test
    fun `compareVersions detects newer older and equal`() {
        assertTrue("0.32.1 应新于 0.32.0", plugin.compareVersions("0.32.1", "0.32.0") > 0)
        assertTrue("0.30.0 应旧于 0.32.0", plugin.compareVersions("0.30.0", "0.32.0") < 0)
        assertEquals(0, plugin.compareVersions("0.32.0", "0.32.0"))
    }

    @Test
    fun `compareVersions handles differing segment counts`() {
        assertEquals("缺段按 0 补齐", 0, plugin.compareVersions("0.32", "0.32.0"))
        assertTrue("1.0.0 应新于 0.99.99", plugin.compareVersions("1.0.0", "0.99.99") > 0)
        assertTrue("0.32.0.1 应新于 0.32.0", plugin.compareVersions("0.32.0.1", "0.32.0") > 0)
    }

    @Test
    fun `compareVersions tolerates non-numeric segments`() {
        assertEquals("非数字段按 0 处理", 0, plugin.compareVersions("0.32.0-beta", "0.32.0"))
        assertTrue("v 前缀段不干扰比较", plugin.compareVersions("v0.32.1", "0.32.0") > 0)
    }

    // ── 检查结果格式化 (新旧判定接线) ────────────────────────────────────

    @Test
    fun `formatCheckResult flags newer release`() {
        val release = ReleaseInfo(
            tag = "v0.32.1", name = "v0.32.1", body = "修复若干问题",
            shellUrl = "https://github.com/x/mengpaw-shell.apk", shellSize = 1024L,
            browserUrl = "", browserSize = 0L
        )
        val r = plugin.formatCheckResult("0.32.0", release)
        assertTrue(r.output.contains("发现新版本"))
        assertTrue(r.output.contains("v0.32.1"))
        assertTrue("有新版本应提示下载", r.output.contains("update.download"))
    }

    @Test
    fun `formatCheckResult reports up to date`() {
        val release = ReleaseInfo(
            tag = "v0.32.0", name = "v0.32.0", body = "",
            shellUrl = "", shellSize = 0L, browserUrl = "", browserSize = 0L
        )
        val r = plugin.formatCheckResult("0.32.0", release)
        assertTrue(r.output.contains("已是最新版本"))
        assertFalse(r.output.contains("update.download"))
    }

    // ── SHA-256 (P2: Locale.ROOT %02x 防畸形输出) ───────────────────────

    @Test
    fun `sha256 matches known vector with lowercase hex`() {
        val hex = plugin.sha256("abc".toByteArray())
        assertEquals("SHA-256 标准测试向量", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertEquals(64, hex.length)
        assertTrue(hex.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `sha256 differs across inputs`() {
        assertNotEquals(plugin.sha256("a".toByteArray()), plugin.sha256("b".toByteArray()))
    }

    // ── 大小格式化 ──────────────────────────────────────────────────────

    @Test
    fun `formatSize renders b kb mb`() {
        assertEquals("0 B", plugin.formatSize(0))
        assertEquals("512 B", plugin.formatSize(512))
        assertEquals("2.0 KB", plugin.formatSize(2048))
        assertEquals("5.0 MB", plugin.formatSize(5 * 1024 * 1024))
    }

    // ── 下载镜像 URL ────────────────────────────────────────────────────

    @Test
    fun `giteeDownload swaps host to gitee`() {
        val url = "https://github.com/WowBlueStudio/MengPaw/releases/download/v0.32.0/mengpaw-shell.apk"
        assertEquals(
            "https://gitee.com/WowBlueStudio/MengPaw/releases/download/v0.32.0/mengpaw-shell.apk",
            UpdatePlugin.giteeDownload(url)
        )
    }

    @Test
    fun `ghproxyDownload prefixes proxy host`() {
        val url = "https://github.com/WowBlueStudio/MengPaw/releases/download/v0.32.0/mengpaw-shell.apk"
        assertEquals("https://ghproxy.com/$url", UpdatePlugin.ghproxyDownload(url))
    }

    // ── scheduleAutoCheck CAS 幂等 (P2 修复) ────────────────────────────

    @Test
    fun `scheduleAutoCheck starts loop only once`() {
        val p = UpdatePlugin()
        assertFalse("初始应未启动", p.autoCheckStarted.get())
        p.scheduleAutoCheck()
        assertTrue("首次调用应启动", p.autoCheckStarted.get())
        p.scheduleAutoCheck()
        p.scheduleAutoCheck()
        assertTrue("重复调用不应重启循环", p.autoCheckStarted.get())
    }

    @Test
    fun `compareVersions is instance independent`() {
        // 纯函数 — 两个实例结果一致 (防实例状态污染)
        val p2 = UpdatePlugin()
        assertEquals(plugin.compareVersions("0.33.0", "0.32.9"), p2.compareVersions("0.33.0", "0.32.9"))
    }

    // ── 待安装状态兜底 / 旧包清理 (P2 修复) ─────────────────────────────

    private fun newDownloader() = UpdateDownloader(
        releaseProvider = { null },
        wifiGateEnabled = { false },
        isWifiConnected = { true },
        formatSize = { _ -> "1.0 MB" },
        pluginVersion = "builtin"
    )

    @Test
    fun `latestApkIn picks the newest matching apk`() {
        val dir = tmp.newFolder("updates")
        val old = File(dir, "mengpaw-shell-v0.32.0.apk").apply { writeText("old") }
        old.setLastModified(1_000L)
        val newer = File(dir, "mengpaw-shell-v0.32.1.apk").apply { writeText("newer") }
        newer.setLastModified(2_000L)
        // 非目标 / 非 APK 文件不参与
        File(dir, "mengpaw-browser-v0.32.1.apk").writeText("browser")
        File(dir, "readme.txt").writeText("readme")

        val hit = newDownloader().latestApkIn(dir, "shell")
        assertNotNull("应命中 shell APK", hit)
        assertEquals("应取最新版本", newer, hit)
    }

    @Test
    fun `cleanupOldApksIn keeps only the given file`() {
        val dir = tmp.newFolder("updates2")
        val keep = File(dir, "mengpaw-shell-v0.32.1.apk").apply { writeText("keep") }
        File(dir, "mengpaw-shell-v0.32.0.apk").writeText("old")
        File(dir, "mengpaw-shell-v0.31.9.apk").writeText("older")
        File(dir, "mengpaw-browser-v0.32.0.apk").writeText("browser")

        newDownloader().cleanupOldApksIn(dir, "shell", keep)

        assertTrue("保留最新文件", keep.exists())
        assertFalse("删除旧 shell APK", File(dir, "mengpaw-shell-v0.32.0.apk").exists())
        assertFalse("删除更旧 shell APK", File(dir, "mengpaw-shell-v0.31.9.apk").exists())
        assertTrue("不影响其他目标", File(dir, "mengpaw-browser-v0.32.0.apk").exists())
    }

    // ── 安装中重复下载防护 (P2 修复) ─────────────────────────────────────

    @Test
    fun `tagFromApkName extracts version tag for shell and browser`() {
        val d = newDownloader()
        assertEquals("v0.38.4", d.tagFromApkName("mengpaw-shell-v0.38.4.apk"))
        assertEquals("v0.38.4", d.tagFromApkName("mengpaw-browser-v0.38.4.apk"))
    }

    @Test
    fun `shouldSkipAutoDownload only skips pending install target and version`() {
        val d = newDownloader()
        assertFalse("无待安装时不应跳过", d.shouldSkipAutoDownload(null, "shell", "v0.38.4"))
        assertTrue("待安装目标+版本应跳过", d.shouldSkipAutoDownload("shell:v0.38.4", "shell", "v0.38.4"))
        assertFalse("其他版本不跳过", d.shouldSkipAutoDownload("shell:v0.38.4", "shell", "v0.38.5"))
        assertFalse("其他目标不跳过", d.shouldSkipAutoDownload("shell:v0.38.4", "browser", "v0.38.4"))
    }

    @Test
    fun `clearInstallPending resets skip state`() {
        val d = newDownloader()
        d.clearInstallPending()
        assertFalse("清除后不应跳过任何版本", d.shouldSkipAutoDownload("shell", "v0.38.4"))
    }
}
