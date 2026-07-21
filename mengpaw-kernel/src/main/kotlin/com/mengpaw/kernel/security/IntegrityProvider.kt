// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.security

/**
 * Pluggable integrity verifier for kernel-internal security checks.
 *
 * Android consumers inject IntegrityGuard (APK signature verification);
 * JVM/desktop consumers use the no-op default.
 */
interface IntegrityProvider {
    /** Returns null if the command passes integrity check, or an error message if blocked. */
    fun validateCommand(commandName: String, args: List<String>): String?
}

object NoOpIntegrityProvider : IntegrityProvider {
    override fun validateCommand(commandName: String, args: List<String>): String? = null
}
