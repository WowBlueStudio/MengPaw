// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.serialization.json.*

/**
 * ACP handler for DELEGATE messages — cross-device / cross-agent task delegation.
 *
 * Previously DELEGATE fell through to a generic ack and was never processed.
 * Now: firewall check → write task to inbox → Agent picks it up.
 */
class DelegateHandler : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(AcpMessageType.DELEGATE)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult {
        val task = message.payload.ifBlank { return AcpResult(false, "empty_delegate") }

        // Firewall check (done by AcpServer, but we double-check for safety)
        if (!PromptFirewall.isTrusted(message.from)) {
            val fwCheck = PromptFirewall.check(message.from, task)
            if (fwCheck != null) return AcpResult(false, "blocked", fwCheck)
        }

        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val taskFile = java.io.File(inbox, "delegate_${System.currentTimeMillis()}.md")
            taskFile.writeText(buildString {
                appendLine("# 委派任务")
                appendLine("> 来自: ${message.from}")
                appendLine("> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                appendLine(task)
            })
            return AcpResult(true, "delegate_queued", taskFile.name)
        } catch (e: Exception) {
            return AcpResult(false, "write_failed", e.message ?: "inbox write error")
        }
    }
}
