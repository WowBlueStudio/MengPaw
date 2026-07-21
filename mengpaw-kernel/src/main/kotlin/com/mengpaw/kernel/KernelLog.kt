// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel

/**
 * Pluggable logger interface for kernel code.
 * Android consumers inject an AndroidLog adapter; JVM consumers use ConsoleLogger.
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun e(tag: String, msg: String)
}

class ConsoleLogger : Logger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")
    override fun w(tag: String, msg: String) = println("W/$tag: $msg")
    override fun i(tag: String, msg: String) = println("I/$tag: $msg")
    override fun e(tag: String, msg: String) = println("E/$tag: $msg")
}

/**
 * Global log sink — set once at app startup.
 * Defaults to ConsoleLogger (works on JVM without Android).
 */
object KernelLog {
    @Volatile
    var logger: Logger = ConsoleLogger()
        private set

    fun setLogger(l: Logger) { logger = l }

    fun d(tag: String, msg: String) = logger.d(tag, msg)
    fun w(tag: String, msg: String) = logger.w(tag, msg)
    fun i(tag: String, msg: String) = logger.i(tag, msg)
    fun e(tag: String, msg: String) = logger.e(tag, msg)
}
