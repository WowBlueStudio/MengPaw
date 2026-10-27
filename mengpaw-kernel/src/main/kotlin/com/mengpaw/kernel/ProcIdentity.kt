// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import java.io.File

/**
 * 当前进程身份 (pid) 的**平台安全**读取入口。
 *
 * ## 为什么需要这一层
 * `java.lang.ProcessHandle` 是 **Java 9 API, Android 全平台都没有**。任何**静态引用**
 * (类型字段 / 直接调用 `ProcessHandle.current()`) 在 Android 上都会在**类校验/首次执行**时抛
 * `Failed resolution of: Ljava/lang/ProcessHandle;`(或 `NoClassDefFoundError`) ——
 * 这是不可 catch 的异常语义边界的近似物, 足以让调用点所在整条业务链路直接崩掉。
 *
 * 2026-10-27 线上实况: `ResumeLock` 的构造器默认参数写的是 `ProcessHandle.current().pid()`,
 * 而 `ResumeLock()` 是每个 Agent 任务的**开局必经点** (ReAct 循环第一步), 于是 Android 上
 * **任何任务**一提交就报「执行出错: Failed resolution of: Ljava/lang/ProcessHandle;」。
 * R8 的 `-dontwarn java.lang.ProcessHandle` 只掩盖了构建期告警, 掩盖不了运行期缺类。
 *
 * ## 取值链 (逐级降级, 只读, 不抛)
 * 1. 反射 `java.lang.ProcessHandle.current().pid()` — 桌面 JVM 正常路径;
 * 2. `File("/proc/self")` 解析符号链接末段 — Android / Linux 降级路径 (同进程自身可见);
 * 3. `-1L` — 彻底不可得。**调用方不得依赖 pid 的正负做业务判据** (它只用于诊断/落盘标识)。
 *
 * ## 可测性
 * 结果按进程缓存一次 ([currentPid]); 降级路径单独暴露 [pidFromProcSelf] 供测试直接验证。
 */
object ProcIdentity {

    /** `ProcessHandle` 类 (不存在则 null) — 只在首次访问时解析一次, 全进程共享。 */
    private val handleClass: Class<*>? = runCatching { Class.forName("java.lang.ProcessHandle") }.getOrNull()

    /** 当前平台是否具备 ProcessHandle 能力 (Android 预期 false)。 */
    val hasProcessHandle: Boolean get() = handleClass != null

    /** 进程 pid 缓存 — 同一进程内不会变, 反射/读 proc 只做一次。 */
    private val cachedPid: Long by lazy { resolvePid() }

    /**
     * 当前进程 pid; 平台不可得时为 `-1`。
     *
     * 本方法**任何实现路径都不会抛异常** —— 它是 Agent 开局路径上的基础身份读取,
     * 不得因为平台缺类把任务整体打断。
     */
    fun currentPid(): Long = cachedPid

    /** 反射 `ProcessHandle.current().pid()` — 不可用/失败返回 null。 */
    private fun pidViaProcessHandle(): Long? = try {
        val cls = handleClass
        if (cls == null) null else {
            val current = cls.getMethod("current").invoke(null)
            (cls.getMethod("pid").invoke(current) as? Long)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 从 `/proc/self` 解析 pid (Android / Linux 降级路径)。
     *
     * `/proc/self` 是指向 `/proc/<pid>` 的符号链接: 优先 [File.getCanonicalFile] (真实解析,
     * 不依赖从 `ls -l` 出来的字符串格式), 再退回文件名本身为数字的情形。失败返回 null。
     */
    internal fun pidFromProcSelf(): Long? = try {
        val self = File("/proc/self")
        if (!self.exists()) null else {
            val canonical = self.canonicalFile.name.toLongOrNull()
            canonical ?: self.name.toLongOrNull()
        }
    } catch (_: Throwable) {
        null
    }

    private fun resolvePid(): Long = pidViaProcessHandle() ?: pidFromProcSelf() ?: -1L
}
