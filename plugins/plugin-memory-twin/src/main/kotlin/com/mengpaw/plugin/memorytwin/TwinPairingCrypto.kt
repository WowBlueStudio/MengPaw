// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import java.security.MessageDigest

/**
 * 配对密码学工具 — 从 TwinPairingEngine 拆分 (无状态纯函数)。
 *
 * nonce 生成 / 6 位验证码 / 确认签名。行为与拆分前完全一致。
 */
internal object TwinPairingCrypto {

    /** Generate a random 32-character hex nonce. */
    fun generateNonce(): String {
        val bytes = ByteArray(16)
        kotlin.random.Random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute a 6-digit verification code from two nonces.
     * Both devices compute this independently — if they match, there's no MITM.
     *
     * Algorithm: SHA-256(nonceA|nonceB) → first 3 bytes as hex → parse as int → mod 1,000,000
     */
    fun computeVerificationCode(nonceA: String, nonceB: String): String {
        val sorted = listOf(nonceA, nonceB).sorted()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${sorted[0]}|${sorted[1]}".toByteArray(Charsets.UTF_8))
        // Take first 3 bytes, convert to unsigned int, mod 1,000,000
        val value = ((hash[0].toInt() and 0xFF) shl 16) or
                    ((hash[1].toInt() and 0xFF) shl 8) or
                    (hash[2].toInt() and 0xFF)
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    /** Compute a signature for PAIR_CONFIRM verification. */
    fun computeSignature(myFp: String, peerFp: String): String {
        val sorted = listOf(myFp, peerFp).sorted()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest("pair_confirm:${sorted[0]}|${sorted[1]}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
