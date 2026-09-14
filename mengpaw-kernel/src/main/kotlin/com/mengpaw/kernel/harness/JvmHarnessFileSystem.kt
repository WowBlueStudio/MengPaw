// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

/**
 * [HarnessFileSystem] 的 JVM 实现 — 行为与旧 `java.io.File` 直接调用等价。
 *
 * A 阶段定位: 默认实现。Android 侧与 JVM 侧共用 (java.io.File 在 Android 同样可用),
 * 仅当未来出现"抽象路径"宿主 (如浏览器沙箱、iOS NSFileManager) 才需要第二实现。
 * 所有方法不吞异常语义: readText 失败照常抛, 与旧实现一致, 便于调用方沿用既有 try/catch。
 */
object JvmHarnessFileSystem : HarnessFileSystem {

    override fun exists(path: String): Boolean = java.io.File(path).exists()

    override fun isDirectory(path: String): Boolean = java.io.File(path).isDirectory

    override fun readText(path: String): String = java.io.File(path).readText()

    override fun writeText(path: String, content: String, createParentDirs: Boolean) {
        val file = java.io.File(path)
        if (createParentDirs) file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun appendText(path: String, content: String, createParentDirs: Boolean) {
        val file = java.io.File(path)
        if (createParentDirs) file.parentFile?.mkdirs()
        file.appendText(content)
    }

    override fun mkdirs(path: String): Boolean {
        val dir = java.io.File(path)
        return dir.mkdirs() || dir.isDirectory
    }

    override fun list(path: String): List<String> {
        val dir = java.io.File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.list()?.toList() ?: emptyList()
    }

    override fun delete(path: String): Boolean = java.io.File(path).delete()

    override fun size(path: String): Long {
        val file = java.io.File(path)
        return if (file.exists()) file.length() else -1L
    }

    override fun lastModified(path: String): Long {
        val file = java.io.File(path)
        return if (file.exists()) file.lastModified() else 0L
    }
}
