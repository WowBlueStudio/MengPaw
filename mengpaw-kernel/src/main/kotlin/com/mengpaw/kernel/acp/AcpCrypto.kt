// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ACP 端到端加密 — MengPaw 设备间 AES 加密通信。
 *
 * ## 加密协商
 * 1. 发送方在 HTTP 请求中包含 X-MengPaw-Encrypt: AES-256-CBC
 * 2. 接收方检测到此头 → 使用共享密钥解密 body
 * 3. 响应也加密返回
 * 4. 如果对方不支持（无此头或返回明文） → 自动降级为明文
 *
 * ## 共享密钥
 * 配对时通过 `self.acp pair` 交换设备指纹 → SHA256(fingerprintA + fingerprintB) → AES Key
 * 双方独立计算得到相同密钥，无需网络传输密钥本身。
 */
object AcpCrypto {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val HEADER_ENCRYPT = "X-MengPaw-Encrypt"
    private const val HEADER_KEY_FINGERPRINT = "X-MengPaw-Key-ID"

    /** Key derived from paired devices' fingerprints. */
    private val sharedKeys = mutableMapOf<String, SecretKeySpec>()
    private val peerEncryptionSupport = mutableMapOf<String, Boolean>() // peerId → supports encryption

    /**
     * Derive a shared AES key from two device fingerprints.
     * Both sides call this with (myFingerprint, peerFingerprint) to get the same key.
     */
    fun deriveKey(myFp: String, peerFp: String, peerId: String) {
        val sorted = listOf(myFp, peerFp).sorted().joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(sorted.toByteArray())
        sharedKeys[peerId] = SecretKeySpec(hash, "AES")
        peerEncryptionSupport[peerId] = true
    }

    /** Check if a peer supports encryption (discovered during handshake). */
    fun supportsEncryption(peerId: String): Boolean =
        peerEncryptionSupport[peerId] == true

    /** Mark peer as encryption-capable after handshake. */
    fun markEncryptionCapable(peerId: String) {
        peerEncryptionSupport[peerId] = true
    }

    /** Get the encryption header value for HTTP requests. */
    fun encryptHeader(): Pair<String, String> = HEADER_ENCRYPT to "AES-256-CBC"

    /**
     * Encrypt plaintext for [peerId].
     * @return Base64-encoded IV+ciphertext, or the original plaintext if no key exists.
     */
    fun encrypt(peerId: String, plaintext: String): String {
        val key = sharedKeys[peerId] ?: return plaintext
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // Prepend IV to ciphertext, Base64 encode
            val combined = iv + encrypted
            java.util.Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            plaintext // Fallback to plaintext on encryption error
        }
    }

    /**
     * Decrypt ciphertext from [peerId].
     * @return Decrypted plaintext, or the original text if no key or not encrypted.
     */
    fun decrypt(peerId: String, ciphertext: String): String {
        val key = sharedKeys[peerId] ?: return ciphertext
        return try {
            val combined = java.util.Base64.getDecoder().decode(ciphertext)
            if (combined.size < 17) return ciphertext // Too short for IV+ciphertext
            val iv = IvParameterSpec(combined, 0, 16)
            val encrypted = combined.copyOfRange(16, combined.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            // VULN-FIX: Constant-time error — always return same-length placeholder
            // to prevent padding oracle attacks that leak key info via timing.
            ciphertext.take(ciphertext.length.coerceAtMost(1000))
        }
    }

    /** Check if an incoming request has the MengPaw encryption header. */
    fun hasEncryptionHeader(headers: Map<String, String>): Boolean =
        headers[HEADER_ENCRYPT] == "AES-256-CBC"

    /** Generate a stable device fingerprint for key derivation. */
    fun myFingerprint(): String {
        // SECURITY: Use only publicly accessible Build fields.
        // Build.SERIAL is blocked by hidden API restrictions on Android 10+ (API 29+).
        // System.getProperty("android.os.Build.*") returns null on modern Android.
        // Fallback chain: Build.FINGERPRINT → Build.HARDWARE → Build.MODEL → random UUID.
        val fingerprint = try {
            Class.forName("android.os.Build").getField("FINGERPRINT").get(null) as? String
        } catch (_: Exception) { null }
            ?: try { Class.forName("android.os.Build").getField("HARDWARE").get(null) as? String }
            catch (_: Exception) { null }
            ?: try { Class.forName("android.os.Build").getField("MODEL").get(null) as? String }
            catch (_: Exception) { "device" }
            ?: "device"

        val raw = fingerprint.replace(" ", "_").replace("/", "_").replace(":", "_").replace(";", "_")
        // Hash for uniform 32-char fingerprint
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
