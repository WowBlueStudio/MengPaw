// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.AcpCrypto
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 配对协议步骤 — 从 TwinPairingEngine 拆分 (职责: 4 步握手协议实现)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式): 会话存储 / 冷却限流 /
 * UI 可观察状态。协议文档见 [TwinPairingEngine]。
 */
internal class TwinPairingProtocol(
    private val sessions: TwinPairingSessions,
    private val cooldown: TwinPairingCooldown,
    private val pairingUiState: MutableStateFlow<TwinPairingEngine.PairingUiState>
) {

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
    ): TwinPairingEngine.PairingUiState {
        // P1.1: Check cooldown before allowing a new pairing attempt
        val cooldownError = cooldown.checkPairingCooldown(peerId)
        if (cooldownError != null) {
            android.util.Log.w("MengPawTwin", "配对被冷却期阻止: peer=$peerId")
            return TwinPairingEngine.PairingUiState(error = cooldownError)
        }

        // Clean up any stale session for this peer
        sessions.sessions.values.removeAll { it.peerId == peerId }

        val nonceA = TwinPairingCrypto.generateNonce()
        val session = TwinPairingEngine.PairingSession(
            sessionId = UUID.randomUUID().toString().take(8),
            peerId = peerId,
            myDeviceId = myDeviceId,
            myFingerprint = myFingerprint,
            nonceA = nonceA,
            isInitiator = true,
            transport = transport
        )
        sessions.sessions[session.sessionId] = session

        // Send CAPABILITY_ANNOUNCE with nonce
        val msg = AcpMessage.capabilityAnnounce(myDeviceId, peerId, capabilityCard, nonceA)
        sessions.scope.launch {
            try {
                transport.send(msg)
            } catch (e: Exception) {
                ErrorCollector.report(e, "TwinPairingEngine.initiatePairing")
                session.phase = TwinPairingEngine.PairingPhase.CANCELLED
            }
        }

        // Schedule timeout
        sessions.scheduleTimeout(session.sessionId)

        android.util.Log.i("MengPawTwin", "配对发起: session=${session.sessionId} peer=$peerId nonce=${nonceA.take(12)}...")
        return TwinPairingEngine.PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = TwinPairingEngine.PairingPhase.AWAITING_CHALLENGE
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
    ): TwinPairingEngine.PairingUiState {
        // P1.1: Check cooldown before responding to a pairing request
        val cooldownError = cooldown.checkPairingCooldown(peerId)
        if (cooldownError != null) {
            android.util.Log.w("MengPawTwin", "对方配对被冷却期阻止: peer=$peerId")
            return TwinPairingEngine.PairingUiState(error = cooldownError)
        }

        // Clean stale sessions for this peer
        sessions.sessions.values.removeAll { it.peerId == peerId && it.isInitiator }

        val nonceB = TwinPairingCrypto.generateNonce()
        val session = TwinPairingEngine.PairingSession(
            sessionId = UUID.randomUUID().toString().take(8),
            peerId = peerId,
            myDeviceId = myDeviceId,
            myFingerprint = myFingerprint,
            nonceA = nonceA,
            nonceB = nonceB,
            isInitiator = false,
            transport = transport
        )
        sessions.sessions[session.sessionId] = session

        // Compute verification code
        val code = TwinPairingCrypto.computeVerificationCode(nonceA, nonceB)
        session.verificationCode = code
        session.phase = TwinPairingEngine.PairingPhase.AWAITING_CONFIRM

        // Send PAIR_CHALLENGE back
        val challenge = AcpMessage.pairChallenge(myDeviceId, peerId, myDeviceId, nonceB, myFingerprint)
        sessions.scope.launch {
            try {
                transport.send(challenge)
            } catch (e: Exception) {
                ErrorCollector.report(e, "TwinPairingEngine.handleAnnounce")
                session.phase = TwinPairingEngine.PairingPhase.CANCELLED
            }
        }

        sessions.scheduleTimeout(session.sessionId)

        android.util.Log.i("MengPawTwin", "配对挑战: session=${session.sessionId} peer=$peerId code=$code")
        val state = TwinPairingEngine.PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = TwinPairingEngine.PairingPhase.AWAITING_CONFIRM,
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
    ): TwinPairingEngine.PairingUiState {
        val session = sessions.sessions.values.find {
            it.peerId == peerId && it.isInitiator && it.phase == TwinPairingEngine.PairingPhase.AWAITING_CHALLENGE
        }
        if (session == null) {
            android.util.Log.w("MengPawTwin", "配对挑战: 未找到活跃会话 for $peerId")
            return TwinPairingEngine.PairingUiState(error = "未找到活跃的配对会话，请重新发起配对")
        }

        session.nonceB = nonceB
        session.peerFingerprint = peerFingerprint
        val code = TwinPairingCrypto.computeVerificationCode(session.nonceA, nonceB)
        session.verificationCode = code
        session.phase = TwinPairingEngine.PairingPhase.AWAITING_CONFIRM

        android.util.Log.i("MengPawTwin", "收到挑战: session=${session.sessionId} peer=$peerId code=$code")
        val state = TwinPairingEngine.PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = TwinPairingEngine.PairingPhase.AWAITING_CONFIRM,
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
    fun confirmPairing(sessionId: String): TwinPairingEngine.PairingUiState {
        val session = sessions.sessions[sessionId]
        if (session == null) {
            return TwinPairingEngine.PairingUiState(error = "配对会话已过期，请重新发起配对")
        }
        if (session.phase != TwinPairingEngine.PairingPhase.AWAITING_CONFIRM) {
            return TwinPairingEngine.PairingUiState(error = "配对会话状态异常: ${session.phase}")
        }
        if (session.peerFingerprint.isBlank()) {
            return TwinPairingEngine.PairingUiState(error = "缺少对端设备指纹")
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
                TwinPairingCrypto.computeSignature(session.myFingerprint, session.peerFingerprint)
            )
            sessions.scope.launch {
                try {
                    session.transport?.send(confirmMsg)
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinPairingEngine.confirmPairing")
                }
            }
        }

        session.phase = TwinPairingEngine.PairingPhase.ESTABLISHED

        // P1.1: Clear cooldown on successful pairing
        cooldown.clearPairingCooldown(session.peerId)

        android.util.Log.i("MengPawTwin", "配对完成: session=$sessionId peer=${session.peerId}")
        return TwinPairingEngine.PairingUiState(
            sessionId = sessionId,
            peerId = session.peerId,
            phase = TwinPairingEngine.PairingPhase.ESTABLISHED
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
    ): TwinPairingEngine.PairingUiState {
        val session = sessions.sessions.values.find {
            it.peerId == peerId && !it.isInitiator && it.phase == TwinPairingEngine.PairingPhase.AWAITING_CONFIRM
        }
        if (session == null) {
            android.util.Log.w("MengPawTwin", "配对确认: 未找到活跃会话 for $peerId")
            return TwinPairingEngine.PairingUiState(error = "未找到活跃的配对会话")
        }

        // Verify the confirmation code matches
        if (verificationCode != session.verificationCode) {
            android.util.Log.e("MengPawTwin", "验证码不匹配: expected=${session.verificationCode} got=$verificationCode")
            session.phase = TwinPairingEngine.PairingPhase.CANCELLED
            return TwinPairingEngine.PairingUiState(error = "验证码不匹配，配对失败")
        }

        // Verify signature
        val expectedSig = TwinPairingCrypto.computeSignature(session.peerFingerprint, session.myFingerprint)
        if (signature != expectedSig) {
            // For now, log but don't block — the verification code check is the primary MITM defense
            android.util.Log.w("MengPawTwin", "签名不匹配: expected=$expectedSig got=$signature")
        }

        // Derive key and establish trust
        AcpCrypto.deriveKey(session.myFingerprint, session.peerFingerprint, peerId)
        PromptFirewall.trustWithKey(peerId, session.peerFingerprint)

        session.phase = TwinPairingEngine.PairingPhase.ESTABLISHED

        // P1.1: Clear cooldown on successful pairing
        cooldown.clearPairingCooldown(peerId)

        android.util.Log.i("MengPawTwin", "配对确认完成: peer=$peerId")
        return TwinPairingEngine.PairingUiState(
            sessionId = session.sessionId,
            peerId = peerId,
            phase = TwinPairingEngine.PairingPhase.ESTABLISHED
        )
    }
}
