// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * IntegrityGuard 桌面路径 (JVM) 测试 — init(context = null) 走 .kt 源文件哈希基线,
 * 不触达 PackageManager/APK 签名 (Android 路径)。构造参数显式传临时目录, 与默认路径解耦。
 *
 * 覆盖: fail-secure (未初始化/无基线 → verify 拒绝)、哈希基线建立与篡改检出、
 * 路径保护前缀 (isProtectedPath / validateCommand)。
 */
class IntegrityGuardTest {

    /** 受跟踪文件清单 — 生产代码私有 (trackedFiles), 测试显式列出;
     *  若生产新增跟踪文件, 本测试初始化后 verify 会因缺文件失败 → 提示同步。 */
    private val TRACKED_FILES = listOf(
        "AgentEngine.kt", "Pipeline.kt", "CommandRegistry.kt",
        "SecurityPolicy.kt", "IntegrityGuard.kt", "Vault.kt",
        "Sanitizer.kt", "PluginManager.kt"
    )

    private lateinit var coreDir: File
    private lateinit var agentsDir: File
    private lateinit var outsideDir: File
    private lateinit var guard: IntegrityGuard

    @Before
    fun setUp() {
        coreDir = Files.createTempDirectory("ig-core-").toFile()
        agentsDir = Files.createTempDirectory("ig-agents-").toFile()
        outsideDir = Files.createTempDirectory("ig-outside-").toFile()
        guard = IntegrityGuard(coreDir = coreDir.absolutePath, agentsDir = agentsDir.absolutePath)
    }

    @After
    fun tearDown() {
        coreDir.deleteRecursively()
        agentsDir.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    /** 写入全部受跟踪文件 (带固定内容, 保证哈希可复现)。 */
    private fun writeAllTrackedFiles(content: String = "v1") {
        TRACKED_FILES.forEach { name -> File(coreDir, name).writeText(content) }
    }

    // ── fail-secure 语义 ──

    @Test
    fun 未初始化verify返回false_failSecure() {
        assertFalse("从未 init 必须拒绝 (fail-secure)", guard.verify())
    }

    @Test
    fun 初始化但无基线文件verify返回false() {
        guard.init() // 核心目录为空 → baselineHashes 空 → 无法验证 → 拒绝
        assertFalse("无 baseline = 无法验证 = 拒绝", guard.verify())
    }

    // ── 桌面哈希基线: 建立 / 篡改 / 删除 ──

    @Test
    fun 全部跟踪文件一致时verify通过() {
        writeAllTrackedFiles()
        guard.init()
        assertTrue("基线建立后完整性应 INTACT", guard.verify())
    }

    @Test
    fun 篡改任一跟踪文件verify返回false() {
        writeAllTrackedFiles()
        guard.init()
        File(coreDir, "Vault.kt").writeText("tampered content")
        assertFalse("篡改后必须检出", guard.verify())
    }

    @Test
    fun 删除跟踪文件verify返回false() {
        writeAllTrackedFiles()
        guard.init()
        File(coreDir, "AgentEngine.kt").delete()
        assertFalse("文件缺失必须检出", guard.verify())
    }

    @Test
    fun 修改非跟踪文件不影响完整性() {
        writeAllTrackedFiles()
        guard.init()
        File(coreDir, "Readme.txt").writeText("随便写")
        assertTrue("非跟踪文件不参与校验", guard.verify())
    }

    @Test
    fun manifest反映完整性状态() {
        writeAllTrackedFiles()
        guard.init()
        val manifest = guard.getManifest()
        assertTrue(manifest.contains("INTACT"))
        assertTrue(manifest.contains(coreDir.absolutePath))
        assertTrue(manifest.contains("Vault.kt"))
    }

    // ── 受保护路径前缀 ──

    @Test
    fun 核心目录内路径受保护() {
        assertTrue(guard.isProtectedPath("${coreDir.absolutePath}${File.separator}AgentEngine.kt"))
        assertTrue(guard.isProtectedPath("${coreDir.absolutePath}${File.separator}sub${File.separator}deep.txt"))
        assertTrue("目录本身也受保护", guard.isProtectedPath(coreDir.absolutePath))
    }

    @Test
    fun 目录外路径不受保护() {
        assertFalse(guard.isProtectedPath(outsideDir.absolutePath))
        assertFalse(guard.isProtectedPath("${outsideDir.absolutePath}${File.separator}x.txt"))
    }

    @Test
    fun 相似前缀目录不误伤() {
        // "ig-core-" 前缀相同但实际是另一目录 — 不得误判 (路径必须完整前缀匹配)
        val sibling = Files.createTempDirectory("ig-core-other").toFile()
        try {
            assertFalse("相似前缀目录不得误判为受保护", guard.isProtectedPath(sibling.absolutePath))
        } finally {
            sibling.deleteRecursively()
        }
    }

    // ── validateCommand 拦截矩阵 ──

    @Test
    fun 写命令到受保护路径被拦截() {
        val dest = "${coreDir.absolutePath}${File.separator}Vault.kt"
        assertNotNull(guard.validateCommand("fs.write", listOf(dest)))
        assertNotNull(guard.validateCommand("fs.cp", listOf("/tmp/src.txt", dest)))
        assertNotNull(guard.validateCommand("fs.mkdir", listOf(dest)))
    }

    @Test
    fun 删除命令到受保护路径被拦截() {
        val target = "${coreDir.absolutePath}${File.separator}Vault.kt"
        assertNotNull(guard.validateCommand("fs.rm", listOf(target)))
    }

    @Test
    fun 移动命令目标为受保护路径被拦截() {
        val dest = "${agentsDir.absolutePath}${File.separator}x.txt"
        assertNotNull(guard.validateCommand("fs.mv", listOf("/tmp/src.txt", dest)))
        // fs.cp 同时属于写命令与移动命令 — 目标受保护同样拦截
        assertNotNull(guard.validateCommand("fs.cp", listOf("/tmp/src.txt", dest)))
    }

    @Test
    fun 写命令到目录外路径放行() {
        val dest = "${outsideDir.absolutePath}${File.separator}ok.txt"
        assertNull(guard.validateCommand("fs.write", listOf(dest)))
        assertNull(guard.validateCommand("fs.rm", listOf("${outsideDir.absolutePath}${File.separator}x.txt")))
    }

    @Test
    fun 命令大小写不敏感() {
        val dest = "${coreDir.absolutePath}${File.separator}Vault.kt"
        assertNotNull("大写命令同样拦截", guard.validateCommand("FS.WRITE", listOf(dest)))
    }

    @Test
    fun 非文件命令不受保护检查() {
        assertNull(guard.validateCommand("llm.chat", listOf("你好")))
        assertNull(guard.validateCommand("fs.write", emptyList()))
    }
}
