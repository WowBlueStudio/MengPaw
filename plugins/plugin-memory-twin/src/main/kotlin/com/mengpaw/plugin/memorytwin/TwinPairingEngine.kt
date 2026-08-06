// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.AcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Memory Twin pairing engine — short-code verification + fingerprint key exchange.
 *
 * ## Protocol (4-step)
 *
 * ```
 * Initiator (A)                          Responder (B)
 *    │                                        │
 *    │ ① CAPABILITY_ANNOUNCE + nonceA         │
 *    │──────────────────────────────────────→│
 *    │                                        │ ② User accepts → generate nonceB
 *    │←─── PAIR_CHALLENGE + nonceB + fpB ────│
 *    │                                        │
 *    │ ③ Both show 6-digit verification code  │
 *    │    code = SHA256(nonceA|nonceB)[0:3]   │
 *    │    as hex → parse as int % 1_000_000   │
 *    │                                        │
 *    │ ④ User confirms codes match            │
 *    │──── PAIR_CONFIRM ────────────────────→│
 *    │                                        │
 *    │ ⑤ deriveKey(fpA, fpB) → AES-256 key   │
 *    │    trust peer → encrypted channel      │
 * ```
 *
 * ## Security properties
 * - **Short-code verification**: Like Bluetooth pairing, user compares 6-digit codes to prevent MITM
 * - **Nonce anti-replay**: Each session has unique nonce pair, PAIR_CONFIRM includes signature
 * - **Key derivation**: AcpCrypto.deriveKey(fpA, fpB) → both sides compute the same AES-256 key
 * - **Trust persistence**: PromptFirewall.trustWithKey() → .trusted + .key files on disk
 * - **Session timeout**: 120s auto-cleanup for stale sessions
 *
 * ## 职责拆分 (批次3)
 * 协议步骤 / 会话存储 / 冷却限流 / 密码学工具拆到同包委托对象,
 * 本对象保留公开 API 签名 (UI/ACP handler 不可感知任何变化):
 * - [TwinPairingProtocol] — 4 步握手协议实现
 * - [TwinPairingSessions] — 会话生命周期 (超时/取消/拒绝/清理)
 * - [TwinPairingCooldown] — 配对限流 (P1.1)
 * - [TwinPairingCrypto] — nonce / 验证码 / 签名纯函数
 */
object TwinPairingEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionStore = TwinPairingSessions(scope)
    private val cooldown = TwinPairingCooldown()

    /** Observable UI state for the current pairing flow. UI collects this to show dialogs. */
    val pairingUiState = kotlinx.coroutines.flow.MutableStateFlow(PairingUiState())

    private val protocol = TwinPairingProtocol(sessionStore, cooldown, pairingUiState)

    // ── Data types ──────────────────────────────────────────────────

    /** Phases of the pairing handshake. */
    enum class PairingPhase {
        /** Waiting for responder to send PAIR_CHALLENGE. */
        AWAITING_CHALLENGE,
        /** Challenge received, verification code shown, waiting for user confirmation. */
        AWAITING_CONFIRM,
        /** Both sides confirmed, key derived, trust established. */
        ESTABLISHED,
        /** Session expired or rejected. */
        CANCELLED
    }

    /**
     * A single pairing session between two devices.
     *
     * @property sessionId Unique session identifier (UUID)
     * @property peerId Remote device identifier
     * @property myDeviceId This device's fingerprint
     * @property myFingerprint This device's full fingerprint (for key derivation)
     * @property nonceA Initiator's random nonce (set by initiator)
     * @property nonceB Responder's random nonce (set when challenge received)
     * @property peerFingerprint Remote device's fingerprint (set when challenge received)
     * @property phase Current handshake phase
     * @property verificationCode Computed 6-digit code (set after challenge)
     * @property isInitiator Whether this device initiated the pairing
     * @property createdAt Session creation timestamp
     * @property transport ACP transport for sending messages
     */
    data class PairingSession(
        val sessionId: String = java.util.UUID.randomUUID().toString().take(8),
        val peerId: String,
        val myDeviceId: String,
        val myFingerprint: String,
        var nonceA: String = "",
        var nonceB: String = "",
        var peerFingerprint: String = "",
        var phase: PairingPhase = PairingPhase.AWAITING_CHALLENGE,
        var verificationCode: String = "",
        val isInitiator: Boolean = true,
        val createdAt: Long = System.currentTimeMillis(),
        var transport: AcpTransport? = null
    )

    /** Result returned to UI after a pairing step completes. */
    data class PairingUiState(
        val sessionId: String = "",
        val peerId: String = "",
        val peerName: String = "",
        val phase: PairingPhase = PairingPhase.AWAITING_CHALLENGE,
        val verificationCode: String = "",
        val error: String = ""
    )

    // ── Public API (delegated to TwinPairingProtocol) ───────────────

    /**
     * Step ① (Initiator): Send CAPABILITY_ANNOUNCE with a random nonce.
     *
     * @param peerId Target peer identifier
     * @param myDeviceId This device's fingerprint (from AcpCrypto.myFingerprint())
     * @param myFingerprint This device's full fingerprint for key derivation
     * @param capabilityCard JSON capability card string
     * @param transport ACP transport to send the message
     * @return PairingUiState with sessionId and phase=AWAITING_CHALLENGE
     */
    fun initiatePairing(
        peerId: String,
        myDeviceId: String,
        myFingerprint: String,
        capabilityCard: String,
        transport: AcpTransport
    ): PairingUiState = protocol.initiatePairing(peerId, myDeviceId, myFingerprint, capabilityCard, transport)

    /**
     * Step ② (Responder): Handle incoming CAPABILITY_ANNOUNCE, generate challenge.
     * Called when we receive a pairing request from another device.
     *
     * @param peerId The device that sent the CAPABILITY_ANNOUNCE
     * @param nonceA The nonce from the initiator's announce
     * @param myDeviceId This device's fingerprint
     * @param myFingerprint This device's full fingerprint
     * @param transport ACP transport to reply
     * @return PairingUiState with verification code ready for display
     */
    fun handleAnnounce(
        peerId: String,
        nonceA: String,
        myDeviceId: String,
        myFingerprint: String,
        transport: AcpTransport
    ): PairingUiState = protocol.handleAnnounce(peerId, nonceA, myDeviceId, myFingerprint, transport)

    /**
     * Step ②/③ (Initiator): Handle PAIR_CHALLENGE from responder.
     * Computes the verification code and returns it for display.
     *
     * @param peerId Responder's device ID
     * @param nonceB Responder's nonce
     * @param peerFingerprint Responder's fingerprint for key derivation
     * @return PairingUiState with verification code ready for user comparison
     */
    fun handleChallenge(
        peerId: String,
        nonceB: String,
        peerFingerprint: String
    ): PairingUiState = protocol.handleChallenge(peerId, nonceB, peerFingerprint)

    /**
     * Step ④ (Both sides): User confirmed codes match. Derive key + establish trust.
     *
     * @param sessionId The pairing session ID
     * @return PairingUiState with phase=ESTABLISHED on success
     */
    fun confirmPairing(sessionId: String): PairingUiState = protocol.confirmPairing(sessionId)

    /**
     * Step ④ (Responder): Handle PAIR_CONFIRM from initiator.
     * Verifies the signature and establishes trust.
     *
     * @param peerId Initiator's device ID
     * @param verificationCode The code that was sent back (for validation)
     * @param signature The initiator's signature for verification
     * @return PairingUiState with phase=ESTABLISHED on success
     */
    fun handleConfirm(
        peerId: String,
        verificationCode: String,
        signature: String
    ): PairingUiState = protocol.handleConfirm(peerId, verificationCode, signature)

    // ── Public API (delegated to TwinPairingSessions) ───────────────

    /**
     * Cancel an active pairing session.
     */
    fun cancelPairing(sessionId: String) = sessionStore.cancel(sessionId)

    /**
     * Reject a pairing request (responder-side cancel).
     */
    fun rejectPairing(peerId: String) = sessionStore.reject(peerId)

    /**
     * Get the current pairing state for UI display.
     */
    fun getSession(sessionId: String): PairingSession? = sessionStore.getSession(sessionId)

    /**
     * Find an active session for a given peer.
     */
    fun getSessionForPeer(peerId: String): PairingSession? = sessionStore.getSessionForPeer(peerId)

    /**
     * Compute a 6-digit verification code from two nonces.
     * Both devices compute this independently — if they match, there's no MITM.
     *
     * Algorithm: SHA-256(nonceA|nonceB) → first 3 bytes as hex → parse as int → mod 1,000,000
     */
    fun computeVerificationCode(nonceA: String, nonceB: String): String =
        TwinPairingCrypto.computeVerificationCode(nonceA, nonceB)

    /** Clear cooldown state for a peer (called on successful pairing). */
    fun clearPairingCooldown(peerId: String) = cooldown.clearPairingCooldown(peerId)

    /** Clean up all expired sessions. */
    fun cleanup() = sessionStore.cleanup()
}
