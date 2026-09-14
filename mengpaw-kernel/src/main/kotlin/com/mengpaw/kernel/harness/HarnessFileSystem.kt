// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

/**
 * Harness 平台抽象 — 文件系统 (A 阶段, 2026-08-21)。
 *
 * 存在意义: kernel 现有 56 个文件直接调 `java.io.File` (324 处), 无法编译到
 * iOS/JS/Wasm 等非 JVM 目标。本接口是 ReAct 核心与宿主文件系统之间的唯一边界 —
 * **签名不出现任何 java.* / android.* 类型**, 保证抽象层本身可 KMP 编译。
 *
 * 设计约束:
 * - 路径一律用 [String] 传递 (平台隔离在实现内部), 不引用 java.io.File。
 * - 不做路径规范化语义承诺 — 相对路径解析交给实现。
 * - 实现必须线程安全 (ReAct 循环与并行 worker 会并发读写)。
 * - 默认实现 [JvmHarnessFileSystem] 保持与旧 `java.io.File` 调用等价的行为。
 */
interface HarnessFileSystem {

    /** 路径是否存在 (文件或目录)。 */
    fun exists(path: String): Boolean

    /** 路径是否为目录。 */
    fun isDirectory(path: String): Boolean

    /** 读取全文 (UTF-8)。文件不存在或读取失败抛异常, 由调用方 try/catch。 */
    fun readText(path: String): String

    /**
     * 写入全文 (UTF-8), 覆盖已有内容。
     * @param createParentDirs 为 true 时自动创建父目录 (等价旧 `File.mkdirs()`)。
     */
    fun writeText(path: String, content: String, createParentDirs: Boolean = true)

    /** 追加内容 (UTF-8), 文件不存在则创建。 */
    fun appendText(path: String, content: String, createParentDirs: Boolean = true)

    /** 创建目录 (含父目录)。已存在返回 true。 */
    fun mkdirs(path: String): Boolean

    /** 列出目录下的条目名 (不含路径前缀)。目录不存在或非目录返回空列表。 */
    fun list(path: String): List<String>

    /** 删除文件或空目录。不存在返回 false。 */
    fun delete(path: String): Boolean

    /** 文件大小 (字节)。不存在返回 -1。 */
    fun size(path: String): Long

    /** 最后修改时间 (epoch millis)。不存在返回 0。 */
    fun lastModified(path: String): Long

    /**
     * 按文件名前缀/后缀过滤目录条目 (memory 等列表场景高频使用,
     * 避免调用方反复 list + 字符串过滤)。
     */
    fun listFiltered(path: String, prefix: String = "", suffix: String = ""): List<String> =
        list(path).filter { it.startsWith(prefix) && it.endsWith(suffix) }
}
