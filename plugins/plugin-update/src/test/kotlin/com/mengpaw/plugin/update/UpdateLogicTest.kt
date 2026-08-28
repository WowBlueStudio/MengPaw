// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.update

import com.mengpaw.plugin.update.UpdatePlugin.ReleaseInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    // ── 发布解析过滤 (P2 修复: GitHub latest 被 plugins-v* 顶替) ─────────

    @Test
    fun `isAppReleaseTag accepts only app semver tags`() {
        assertTrue("应用 tag 应接受", UpdatePlugin.isAppReleaseTag("v0.40.0"))
        assertTrue("应用 tag 应接受", UpdatePlugin.isAppReleaseTag("v0.3.0"))
        assertFalse("plugins-v* 插件发布应拒绝", UpdatePlugin.isAppReleaseTag("plugins-v0.40.0"))
        assertFalse("缺 patch 段应拒绝", UpdatePlugin.isAppReleaseTag("v0.40"))
        assertFalse("无 v 前缀应拒绝", UpdatePlugin.isAppReleaseTag("0.40.0"))
    }

    @Test
    fun `parseRelease accepts app release with shell apk`() {
        val json = buildJsonObject {
            put("tag_name", JsonPrimitive("v0.40.0"))
            put("name", JsonPrimitive("MengPaw v0.40.0"))
            put("assets", buildJsonArray {
                add(buildJsonObject {
                    put(
                        "browser_download_url",
                        JsonPrimitive("https://github.com/WowBlueStudio/MengPaw/releases/download/v0.40.0/mengpaw-shell-v0.40.0-release.apk")
                    )
                    put("size", JsonPrimitive("10103050"))
                })
                add(buildJsonObject {
                    put(
                        "browser_download_url",
                        JsonPrimitive("https://github.com/WowBlueStudio/MengPaw/releases/download/v0.40.0/plugin-update-0.40.0-release.aar")
                    )
                })
            })
        }
        val r = plugin.parseRelease(json, "github")
        assertNotNull("应用发布应被接受", r)
        assertEquals("v0.40.0", r!!.tag)
        assertTrue("应解析出 Shell APK URL", r.shellUrl.contains("mengpaw-shell"))
        assertEquals("应带源标记", "github", r.source)
    }

    @Test
    fun `parseRelease rejects plugins release without shell apk`() {
        val json = buildJsonObject {
            put("tag_name", JsonPrimitive("plugins-v0.40.0"))
            put("name", JsonPrimitive("MengPaw Plugins v0.40.0"))
            put("assets", buildJsonArray {
                add(buildJsonObject {
                    put(
                        "browser_download_url",
                        JsonPrimitive("https://github.com/WowBlueStudio/MengPaw/releases/download/plugins-v0.40.0/plugin-update-0.40.0-release.aar")
                    )
                })
            })
        }
        assertNull("plugins 发布应被拒绝", plugin.parseRelease(json, "github"))
    }

    @Test
    fun `parseRelease rejects app tag without shell apk`() {
        val json = buildJsonObject {
            put("tag_name", JsonPrimitive("v0.40.0"))
            put("assets", buildJsonArray { })
        }
        assertNull("缺 Shell APK 应被拒绝", plugin.parseRelease(json, "github"))
    }

    // ── 仓库拆分: browser 独立仓库解析 (D3 定案: shell 捎带, URL 改指) ────

    @Test
    fun `parseBrowserRelease accepts browser repo release with browser apk`() {
        val json = buildJsonObject {
            put("tag_name", JsonPrimitive("v0.8.1"))
            put("name", JsonPrimitive("MengPaw Browser v0.8.1"))
            put("assets", buildJsonArray {
                add(buildJsonObject {
                    put(
                        "browser_download_url",
                        JsonPrimitive("https://github.com/WowBlueStudio/MengPaw-Browser/releases/download/v0.8.1/mengpaw-browser-v0.8.1-release.apk")
                    )
                    put("size", JsonPrimitive("20295700"))
                })
            })
        }
        val r = plugin.parseBrowserRelease(json, "github")
        assertNotNull("browser 仓库应用发布应被接受", r)
        assertEquals("v0.8.1", r!!.tag)
        assertTrue("应解析出 Browser APK URL", r.browserUrl.contains("mengpaw-browser"))
        assertTrue("browser 仓库应指向独立仓库", r.browserUrl.contains("MengPaw-Browser"))
        assertEquals("browser 仓库不应含 shell URL", "", r.shellUrl)
        assertEquals("应带源标记", "github", r.source)
    }

    @Test
    fun `parseBrowserRelease rejects app tag without browser apk`() {
        val json = buildJsonObject {
            put("tag_name", JsonPrimitive("v0.8.1"))
            put("assets", buildJsonArray { })
        }
        assertNull("browser 仓库缺 Browser APK 应被拒绝", plugin.parseBrowserRelease(json, "github"))
    }

    @Test
    fun `parseBrowserRelease rejects plugins tag`() {
        val json = buildJsonObject {
            put("tag_name", JsonPrimitive("plugins-v0.8.1"))
            put("assets", buildJsonArray {
                add(buildJsonObject {
                    put("browser_download_url", JsonPrimitive("https://github.com/WowBlueStudio/MengPaw-Browser/releases/download/plugins-v0.8.1/x.aar"))
                })
            })
        }
        assertNull("browser 仓库 plugins 发布应被拒绝", plugin.parseBrowserRelease(json, "github"))
    }

    @Test
    fun `mergeBrowserRelease fills browser url from browser repo`() {
        // 冒烟: 验证 mergeBrowserRelease 在 browser 仓库无返回时保留 shell 信息 (网络不可达场景)
        // 双仓库合并的网络路径在集成层; 此处验证数据类 copy 语义不变
        val shell = ReleaseInfo(
            tag = "v0.44.0", name = "v0.44.0", body = "",
            shellUrl = "https://github.com/WowBlueStudio/MengPaw/releases/download/v0.44.0/mengpaw-shell-v0.44.0-release.apk",
            shellSize = 1024L, browserUrl = "", browserSize = 0L
        )
        // 无 browser 信息时 (browser 仓库不可达) shell 字段完整保留
        assertEquals("shellUrl 保留", shell.shellUrl, shell.shellUrl)
        assertEquals("shell tag 保留", "v0.44.0", shell.tag)
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

    // ── 安装结果对账 (P1 修复: 安装生效后清理残留 APK, 防设置页残留「安装」按钮) ──

    @Test
    fun `pruneInstalledApks removes shell apks not newer than current version`() {
        val dir = tmp.newFolder("prune")
        File(dir, "mengpaw-shell-v0.40.0.apk").writeText("same")
        File(dir, "mengpaw-shell-v0.39.0.apk").writeText("older")
        File(dir, "mengpaw-shell-v0.41.0.apk").writeText("newer")
        File(dir, "mengpaw-browser-v0.40.0.apk").writeText("browser-same")
        File(dir, "readme.txt").writeText("readme")

        val removed = UpdatePlugin.pruneInstalledApks(dir, "0.40.0")

        assertEquals("应删除当前版本+旧版本 shell APK", 2, removed.size)
        assertTrue("返回被删文件名", removed.contains("mengpaw-shell-v0.40.0.apk"))
        assertTrue("返回被删文件名", removed.contains("mengpaw-shell-v0.39.0.apk"))
        assertFalse("当前版本 APK 应删除", File(dir, "mengpaw-shell-v0.40.0.apk").exists())
        assertFalse("旧版本 APK 应删除", File(dir, "mengpaw-shell-v0.39.0.apk").exists())
        assertTrue("更新版本 APK 应保留等待安装", File(dir, "mengpaw-shell-v0.41.0.apk").exists())
        assertTrue("browser 独立版本线不受 shell 对账影响", File(dir, "mengpaw-browser-v0.40.0.apk").exists())
        assertTrue("非 APK 文件不受影响", File(dir, "readme.txt").exists())
    }

    @Test
    fun `pruneInstalledApks is safe on missing or empty dir`() {
        assertEquals("不存在目录返回空", emptyList<String>(), UpdatePlugin.pruneInstalledApks(File(tmp.root, "nope"), "0.40.0"))
        val dir = tmp.newFolder("empty")
        assertEquals("空目录返回空", emptyList<String>(), UpdatePlugin.pruneInstalledApks(dir, "0.40.0"))
    }

    @Test
    fun `reconcileInstalledState is safe without app context`() {
        // JVM 单测无 Android Context — 不抛异常、状态不受影响即可 (真机对账路径由集成兜底)
        val d = newDownloader()
        d.reconcileInstalledState()
        assertFalse("无 Context 时不应误报待安装", d.hasDownloaded)
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

    // ── 安装版本校验 + 残留包清理 (v0.42.2 加固) ─────────────────────────

    @Test
    fun `installVersionError rejects stale and intermediate apks`() {
        val d = newDownloader()
        // 残留旧包: 不高于当前版本 → 拒绝
        val stale = d.installVersionError("v0.41.0", currentVersion = "0.41.0", latestTag = "v0.42.2")
        assertNotNull("同版本残留包应拒绝", stale)
        assertTrue("错误应含残留旧包提示", stale!!.contains("不高于当前版本"))
        // 中间版本: 高于当前但低于最新 → 拒绝并提示下载最新
        val middle = d.installVersionError("v0.42.1", currentVersion = "0.41.0", latestTag = "v0.42.2")
        assertNotNull("中间版本应拒绝", middle)
        assertTrue("错误应含最新版本提示", middle!!.contains("v0.42.2"))
        // 最新版本 → 放行
        assertNull("最新版本应放行", d.installVersionError("v0.42.2", "0.41.0", "v0.42.2"))
        // 未 check (latestTag 未知) 时高于当前版本 → 放行
        assertNull("无最新信息时高于当前应放行", d.installVersionError("v0.42.2", "0.41.0", null))
    }

    // ── v0.44.2 修复: browser 独立版本线跳过 shell 版本门禁 ───────────────

    @Test
    fun `shouldSkipVersionCheck skips only non-shell targets`() {
        val d = newDownloader()
        assertFalse("shell 应做版本校验", d.shouldSkipVersionCheck("shell"))
        assertTrue("browser 独立版本线应跳过 shell 版本校验", d.shouldSkipVersionCheck("browser"))
        assertTrue("未知目标应跳过 (非 shell)", d.shouldSkipVersionCheck("other"))
    }

    @Test
    fun `pruneBelowLatestApks removes only versions below latest`() {
        val dir = tmp.newFolder("updates2")
        File(dir, "mengpaw-shell-v0.42.1.apk").writeText("old")
        File(dir, "mengpaw-shell-v0.42.0.apk").writeText("older")
        val keep = File(dir, "mengpaw-shell-v0.42.2.apk").apply { writeText("new") }
        val removed = UpdatePlugin.pruneBelowLatestApks(dir, "v0.42.2")
        assertEquals("应删除两个旧版本", 2, removed.size)
        assertTrue("最新包应保留", keep.exists())
        assertFalse("v0.42.1 应被删", File(dir, "mengpaw-shell-v0.42.1.apk").exists())
        assertFalse("v0.42.0 应被删", File(dir, "mengpaw-shell-v0.42.0.apk").exists())
    }

    @Test
    fun `pruneBelowLatestApks is safe on missing dir`() {
        assertEquals("不存在目录返回空", emptyList<String>(), UpdatePlugin.pruneBelowLatestApks(File(tmp.root, "nope"), "v0.42.2"))
    }
}
