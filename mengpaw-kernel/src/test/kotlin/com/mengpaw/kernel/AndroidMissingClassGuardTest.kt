// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Android 缺类门禁 — 禁止生产源码**静态引用** `java.lang.ProcessHandle`。
 *
 * 背景 (2026-10-27 v0.49.x 线上事故): `ResumeLock` 的构造器默认参数写着
 * `ProcessHandle.current().pid()`, 而 `ResumeLock()` 是每个 Agent 任务的开局必经点 ——
 * Android 全平台没有 `ProcessHandle` (Java 9 API), 于是**任何任务**提交即报
 * `执行出错: Failed resolution of: Ljava/lang/ProcessHandle;`。
 *
 * 这条纪律 `docs/harness-android-port.md` / `docs/lessons.md` 早已写明,
 * 但 `ResumeLock.kt` 仍违反了 (R8 `-dontwarn` 只掩盖构建期告警, 掩盖不了运行期缺类) ——
 * 即"文档写了、代码没守"的典型。故用测试锁死: **反射 (字符串 `Class.forName(...)`) 合法,
 * 直接写类型/静态调用非法**。
 *
 * 判定: 逐行剥离注释与字符串字面量后, 仍出现单词 `ProcessHandle` 即命中。
 */
class AndroidMissingClassGuardTest {

    private fun repoRoot(): File =
        listOf(File(".."), File(".")).firstOrNull { File(it, "settings.gradle.kts").exists() } ?: File("..")

    private fun productionSourceDirs(): List<File> {
        val root = repoRoot()
        val modules = listOf("mengpaw-kernel", "mengpaw-core", "mengpaw-shell", "harness")
            .map { File(root, "$it/src/main") }.filter { it.isDirectory }
        val plugins = File(root, "plugins").listFiles { f -> f.isDirectory }
            ?.map { File(it, "src/main") }?.filter { it.isDirectory } ?: emptyList()
        return modules + plugins
    }

    /** 去掉行内 `//` 注释与块注释残余 (`*` 开头行)。 */
    private fun stripComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return ""
        val idx = line.indexOf("//")
        return if (idx >= 0) line.substring(0, idx) else line
    }

    /** 去掉字符串字面量内容 — `Class.forName("java.lang.ProcessHandle")` 属合法反射。 */
    private fun stripStringLiterals(line: String): String =
        line.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"")

    private val FORBIDDEN = Regex("\\bProcessHandle\\b")

    @Test
    fun `生产源码不得静态引用 ProcessHandle`() {
        val dirs = productionSourceDirs()
        assumeTrue("源码目录不存在, 跳过 (非仓库工作目录)", dirs.isNotEmpty())

        val hits = mutableListOf<String>()
        dirs.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { idx, raw ->
                    val code = stripStringLiterals(stripComment(raw))
                    if (FORBIDDEN.containsMatchIn(code)) {
                        hits.add("${file.name}:${idx + 1}: ${raw.trim().take(120)}")
                    }
                }
            }
        }

        assertTrue(
            "发现 ProcessHandle 静态引用 (Android 全平台缺类, 会在运行期 Failed resolution 崩溃)。" +
                "改用 com.mengpaw.kernel.ProcIdentity.currentPid() 或 Class.forName 反射:\n" +
                hits.joinToString("\n"),
            hits.isEmpty()
        )
    }
}
