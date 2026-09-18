// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import java.io.File

/**
 * 进程信息读取 / 终止的平台适配层 (proc.* 的底层)。
 *
 * ## 为什么用反射
 * `java.lang.ProcessHandle` 是 **Java 9 API, Android 全平台都没有** —— 直接静态引用会导致:
 * ① R8 release 构建报 `Missing classes: java.lang.ProcessHandle` 而中断;
 * ② 即使编译通过, 运行时类验证 `NoClassDefFoundError` (不是可 catch 的 Exception 语义那么简单)。
 * 故本层用**反射**访问: 类不存在时 [available] 为 false, 上层优雅降级到 `/proc` 与 Linux 命令。
 *
 * 单测在 JVM 上跑 (有 ProcessHandle) → 覆盖正常路径; Android 设备走降级路径。
 * 反射调用的方法名/签名与 `java.lang.ProcessHandle` 公开 API 一致。
 */
internal object ProcApi {

    /** ProcessHandle 类 (不存在则 null) — 只在首次访问时解析一次。 */
    private val handleClass: Class<*>? = runCatching { Class.forName("java.lang.ProcessHandle") }.getOrNull()

    /** 当前平台是否具备 ProcessHandle 能力 (Android 预期 false)。 */
    val available: Boolean get() = handleClass != null

    /** 一个进程的可读快照。 */
    internal data class Snapshot(
        val pid: Long,
        val cmdline: String,
        val args: String,
        val user: String,
        val alive: Boolean,
        val startInstant: String,
        val parentPid: Long?,
        val children: List<Long>
    )

    /** 枚举全部可见进程 (失败/不可用返回空表)。 */
    fun allProcesses(): List<Any> = try {
        val cls = handleClass ?: return emptyList()
        val stream = cls.getMethod("allProcesses").invoke(null)
        @Suppress("UNCHECKED_CAST")
        (stream as? java.util.stream.Stream<Any>)?.toArray()?.toList() ?: emptyList()
    } catch (e: Throwable) {
        emptyList()
    }

    /** 当前进程的 pid (纯 JVM: `ProcessHandle.current().pid()`; 降级用 `java.lang.Process` 不可得时 -1)。 */
    fun currentPid(): Long = try {
        val cls = handleClass
        if (cls != null) {
            val current = cls.getMethod("current").invoke(null)
            cls.getMethod("pid").invoke(current) as Long
        } else -1L
    } catch (e: Throwable) {
        -1L
    }

    /** 按 pid 取句柄 (不存在/不可见返回 null)。 */
    fun of(pid: Long): Any? = try {
        val cls = handleClass ?: return null
        val optional = cls.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid)
        if (optionalBoolean(optional, "isPresent")) optionalGet(optional) else null
    } catch (e: Throwable) {
        null
    }

    /** 读取单个进程快照 (失败返回 null)。 */
    fun snapshot(handle: Any): Snapshot? = try {
        val cls = handleClass ?: return null
        val pid = cls.getMethod("pid").invoke(handle) as Long
        val info = runCatching { cls.getMethod("info").invoke(handle) }.getOrNull()
        val cmdFromProc = readProcCmdline(pid)
        val cmdFromInfo = optionalString(info, "command") ?: ""
        val args = optionalStringArray(info, "arguments")?.joinToString(" ") ?: ""
        val cmdline = when {
            cmdFromProc.isNotBlank() -> cmdFromProc
            args.isNotBlank() -> "$cmdFromInfo $args".trim()
            else -> cmdFromInfo
        }
        Snapshot(
            pid = pid,
            cmdline = cmdline.take(120).replace('\n', ' '),
            args = args,
            user = optionalString(info, "user") ?: "",
            alive = runCatching { cls.getMethod("isAlive").invoke(handle) as Boolean }.getOrDefault(false),
            startInstant = optionalInstant(info),
            parentPid = runCatching {
                val parent = cls.getMethod("parent").invoke(handle)
                if (optionalBoolean(parent, "isPresent")) (cls.getMethod("pid").invoke(optionalGet(parent)) as Long) else null
            }.getOrNull(),
            children = runCatching {
                val stream = cls.getMethod("children").invoke(handle)
                @Suppress("UNCHECKED_CAST")
                val arr = (stream as? java.util.stream.Stream<Any>)?.toArray() ?: emptyArray()
                arr.mapNotNull { child -> runCatching { cls.getMethod("pid").invoke(child) as Long }.getOrNull() }
            }.getOrDefault(emptyList())
        )
    } catch (e: Throwable) {
        null
    }

    /** 终止进程 — [force] true 用 destroyForcibly。返回是否成功送达信号。 */
    fun destroy(handle: Any, force: Boolean): Boolean = try {
        val cls = handleClass ?: return false
        val method = if (force) "destroyForcibly" else "destroy"
        cls.getMethod(method).invoke(handle) as? Boolean ?: true
    } catch (e: Throwable) {
        false
    }

    // ── Optional / Stream 取值辅助 (避免静态引用 Java 9 类型) ─────────

    private fun optionalGet(optional: Any?): Any? =
        runCatching { optional!!.javaClass.getMethod("get").invoke(optional) }.getOrNull()

    private fun optionalBoolean(optional: Any?, method: String): Boolean =
        runCatching { optional!!.javaClass.getMethod(method).invoke(optional) as Boolean }.getOrDefault(false)

    private fun optionalString(info: Any?, method: String): String? {
        val raw = runCatching { info!!.javaClass.getMethod(method).invoke(info) }.getOrNull() ?: return null
        return optionalGet(raw) as? String
    }

    private fun optionalStringArray(info: Any?, method: String): List<String>? {
        val raw = runCatching { info!!.javaClass.getMethod(method).invoke(info) }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return (optionalGet(raw) as? Array<String>)?.toList()
    }

    private fun optionalInstant(info: Any?): String {
        val raw = runCatching { info!!.javaClass.getMethod("startInstant").invoke(info) }.getOrNull() ?: return "(未知)"
        return optionalGet(raw)?.toString() ?: "(未知)"
    }

    /** 读取 /proc/<pid>/cmdline (NUL 分隔) — Android 上多数进程可读, 失败返回空串。 */
    internal fun readProcCmdline(pid: Long): String = try {
        val f = File("/proc/$pid/cmdline")
        if (f.exists() && f.canRead()) f.readText().replace('\u0000', ' ').trim() else ""
    } catch (_: Exception) {
        ""
    }

    /** 枚举 /proc 下可见的数字 pid 目录 — ProcessHandle 不可用时的降级路径。 */
    internal fun procPids(): List<Long> = try {
        File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?.mapNotNull { it.name.toLongOrNull() }?.sorted() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
