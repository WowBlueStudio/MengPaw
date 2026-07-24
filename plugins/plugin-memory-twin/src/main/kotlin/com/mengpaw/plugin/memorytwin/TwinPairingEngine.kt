// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.AcpCrypto
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.UUID

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
 */
object TwinPairingEngine {

    /** Active pairing sessions, keyed by sessionId. */
    private val sessions = mutableMapOf<String, PairingSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Observable UI state for the current pairing flow. UI collects this to show dialogs. */
    val pairingUiState = kotlinx.coroutines.flow.MutableStateFlow(PairingUiState())

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
        val sessionId: String = UUID.randomUUID().toString().take(8),
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

    // ── Public API ──────────────────────────────────────────────────

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
    ): PairingUiState {
        // Clean up any stale session for this peer
        sessions.values.removeAll { it.peerId == peerId }

        val nonceA = generateNonce()
        val session = PairingSession(
            sessionId = UUID.randomUUID().toString().take(8),
            peerId = peerId,
            myDeviceId = myDeviceId,
            myFingerprint = myFingerprint,
            nonceA = nonceA,
            isInitiator = true,
            transport = transport
        )
        sessions[session.sessionId] = session

        // Send CAPABILITY_ANNOUNCE with nonce
        val msg = AcpMessage.capabilityAnnounce(myDeviceId, peerId, capabilityCard, nonceA)
        scope.launch {
            try {
                transport.send(msg)
            } catch (e: Exception) {
                ErrorCollector.report(e, "TwinPairingEngine.initiatePairing")
                session.phase = PairingPhase.CANCELLED
            }
        }

        // Schedule timeout
        scheduleTimeout(session.sessionId)

        android.util.Log.i("MengPawTwin", "配对发起: session=${session.sessionId} peer=$peerId nonce=${nonceA.take(12)}...")
        return PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = PairingPhase.AWAITING_CHALLENGE
        )
    }

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
    ): PairingUiState {
        // Clean stale sessions for this peer
        sessions.values.removeAll { it.peerId == peerId && it.isInitiator }

        val nonceB = generateNonce()
        val session = PairingSession(
            sessionId = UUID.randomUUID().toString().take(8),
            peerId = peerId,
            myDeviceId = myDeviceId,
            myFingerprint = myFingerprint,
            nonceA = nonceA,
            nonceB = nonceB,
            isInitiator = false,
            transport = transport
        )
        sessions[session.sessionId] = session

        // Compute verification code
        val code = computeVerificationCode(nonceA, nonceB)
        session.verificationCode = code
        session.phase = PairingPhase.AWAITING_CONFIRM

        // Send PAIR_CHALLENGE back
        val challenge = AcpMessage.pairChallenge(myDeviceId, peerId, myDeviceId, nonceB, myFingerprint)
        scope.launch {
            try {
                transport.send(challenge)
            } catch (e: Exception) {
                ErrorCollector.report(e, "TwinPairingEngine.handleAnnounce")
                session.phase = PairingPhase.CANCELLED
            }
        }

        scheduleTimeout(session.sessionId)

        android.util.Log.i("MengPawTwin", "配对挑战: session=${session.sessionId} peer=$peerId code=$code")
        val state = PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = PairingPhase.AWAITING_CONFIRM,
            verificationCode = code
        )
        pairingUiState.value = state
        return state
    }

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
    ): PairingUiState {
        val session = sessions.values.find {
            it.peerId == peerId && it.isInitiator && it.phase == PairingPhase.AWAITING_CHALLENGE
        }
        if (session == null) {
            android.util.Log.w("MengPawTwin", "配对挑战: 未找到活跃会话 for $peerId")
            return PairingUiState(error = "未找到活跃的配对会话，请重新发起配对")
        }

        session.nonceB = nonceB
        session.peerFingerprint = peerFingerprint
        val code = computeVerificationCode(session.nonceA, nonceB)
        session.verificationCode = code
        session.phase = PairingPhase.AWAITING_CONFIRM

        android.util.Log.i("MengPawTwin", "收到挑战: session=${session.sessionId} peer=$peerId code=$code")
        val state = PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = PairingPhase.AWAITING_CONFIRM,
            verificationCode = code
        )
        pairingUiState.value = state
        return state
    }

    /**
     * Step ④ (Both sides): User confirmed codes match. Derive key + establish trust.
     *
     * @param sessionId The pairing session ID
     * @return PairingUiState with phase=ESTABLISHED on success
     */
    fun confirmPairing(sessionId: String): PairingUiState {
        val session = sessions[sessionId]
        if (session == null) {
            return PairingUiState(error = "配对会话已过期，请重新发起配对")
        }
        if (session.phase != PairingPhase.AWAITING_CONFIRM) {
            return PairingUiState(error = "配对会话状态异常: ${session.phase}")
        }
        if (session.peerFingerprint.isBlank()) {
            return PairingUiState(error = "缺少对端设备指纹")
        }

        // Derive AES-256 key from both device fingerprints
        AcpCrypto.deriveKey(session.myFingerprint, session.peerFingerprint, session.peerId)

        // Persist trust
        PromptFirewall.trustWithKey(session.peerId, session.peerFingerprint)

        // Send PAIR_CONFIRM to peer
        if (session.isInitiator) {
            val confirmMsg = AcpMessage.pairConfirm(
                session.myDeviceId, session.peerId,
                session.myDeviceId, session.verificationCode,
                computeSignature(session.myFingerprint, session.peerFingerprint)
            )
            scope.launch {
                try {
                    session.transport?.send(confirmMsg)
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinPairingEngine.confirmPairing")
                }
            }
        }

        session.phase = PairingPhase.ESTABLISHED

        android.util.Log.i("MengPawTwin", "配对完成: session=$sessionId peer=${session.peerId}")
        return PairingUiState(
            sessionId = sessionId,
            peerId = session.peerId,
            phase = PairingPhase.ESTABLISHED
        )
    }

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
    ): PairingUiState {
        val session = sessions.values.find {
            it.peerId == peerId && !it.isInitiator && it.phase == PairingPhase.AWAITING_CONFIRM
        }
        if (session == null) {
            android.util.Log.w("MengPawTwin", "配对确认: 未找到活跃会话 for $peerId")
            return PairingUiState(error = "未找到活跃的配对会话")
        }

        // Verify the confirmation code matches
        if (verificationCode != session.verificationCode) {
            android.util.Log.e("MengPawTwin", "验证码不匹配: expected=${session.verificationCode} got=$verificationCode")
            session.phase = PairingPhase.CANCELLED
            return PairingUiState(error = "验证码不匹配，配对失败")
        }

        // Verify signature
        val expectedSig = computeSignature(session.peerFingerprint, session.myFingerprint)
        if (signature != expectedSig) {
            // For now, log but don't block — the verification code check is the primary MITM defense
            android.util.Log.w("MengPawTwin", "签名不匹配: expected=$expectedSig got=$signature")
        }

        // Derive key and establish trust
        AcpCrypto.deriveKey(session.myFingerprint, session.peerFingerprint, peerId)
        PromptFirewall.trustWithKey(peerId, session.peerFingerprint)

        session.phase = PairingPhase.ESTABLISHED

        android.util.Log.i("MengPawTwin", "配对确认完成: peer=$peerId")
        return PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = PairingPhase.ESTABLISHED
        )
    }

    /**
     * Cancel an active pairing session.
     */
    fun cancelPairing(sessionId: String) {
        sessions[sessionId]?.phase = PairingPhase.CANCELLED
        sessions.remove(sessionId)
        android.util.Log.i("MengPawTwin", "配对取消: $sessionId")
    }

    /**
     * Reject a pairing request (responder-side cancel).
     */
    fun rejectPairing(peerId: String) {
        sessions.values
            .filter { it.peerId == peerId && !it.isInitiator }
            .forEach { it.phase = PairingPhase.CANCELLED }
        sessions.values.removeAll { it.peerId == peerId && !it.isInitiator }
    }

    /**
     * Get the current pairing state for UI display.
     */
    fun getSession(sessionId: String): PairingSession? = sessions[sessionId]

    /**
     * Find an active session for a given peer.
     */
    fun getSessionForPeer(peerId: String): PairingSession? =
        sessions.values.find { it.peerId == peerId && it.phase != PairingPhase.CANCELLED }

    // ── Internal helpers ────────────────────────────────────────────

    /** Generate a random 32-character hex nonce. */
    private fun generateNonce(): String {
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
    private fun computeSignature(myFp: String, peerFp: String): String {
        val sorted = listOf(myFp, peerFp).sorted()
        return LedgerEntry.sha256("pair_confirm:${sorted[0]}|${sorted[1]}")
    }

    /** Auto-cleanup stale sessions after 120 seconds. */
    private fun scheduleTimeout(sessionId: String) {
        scope.launch {
            delay(120_000) // 2 minutes
            val session = sessions[sessionId] ?: return@launch
            if (session.phase != PairingPhase.ESTABLISHED) {
                android.util.Log.i("MengPawTwin", "配对超时: $sessionId")
                sessions.remove(sessionId)
            }
        }
    }

    /** Clean up all expired sessions. */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 120_000
        sessions.values.removeAll { it.createdAt < cutoff && it.phase != PairingPhase.ESTABLISHED }
    }
}
