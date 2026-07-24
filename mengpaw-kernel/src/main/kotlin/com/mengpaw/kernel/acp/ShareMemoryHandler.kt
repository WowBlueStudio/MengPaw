// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.serialization.json.*

/**
 * ACP handler for SHARE_MEMORY and SHARE_SKILL messages.
 *
 * Previously both types fell through to generic ack. Now:
 * - SHARE_MEMORY → write to shared memory store (inbox for Agent review)
 * - SHARE_SKILL → write skill definition to inbox for Agent to install
 */
class ShareMemoryHandler : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.SHARE_MEMORY,
        AcpMessageType.SHARE_SKILL
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult {
        // Firewall: untrusted peers can share memory/skills (read-only for them, reviewed by Agent)
        val isMemory = try { AcpMessageType.valueOf(message.type) == AcpMessageType.SHARE_MEMORY }
            catch (_: Exception) { false }

        return if (isMemory) handleShareMemory(message) else handleShareSkill(message)
    }

    private fun handleShareMemory(msg: AcpMessage): AcpResult {
        val memoryId = msg.payload.ifBlank { return AcpResult(false, "empty_memory") }
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val memFile = java.io.File(inbox, "shared_memory_${System.currentTimeMillis()}.md")
            memFile.writeText(buildString {
                appendLine("# 共享记忆")
                appendLine("> 来自: ${msg.from}")
                appendLine("> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                appendLine(memoryId)
                appendLine()
                appendLine("---")
                appendLine("审查后使用 `agent.memory.record <内容>` 将共享记忆记录到本地。")
            })
            return AcpResult(true, "memory_shared", memFile.name)
        } catch (e: Exception) {
            return AcpResult(false, "write_failed", e.message ?: "inbox write error")
        }
    }

    private fun handleShareSkill(msg: AcpMessage): AcpResult {
        val skillName = msg.payload.ifBlank { return AcpResult(false, "empty_skill") }
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val skillFile = java.io.File(inbox, "shared_skill_${System.currentTimeMillis()}.md")
            skillFile.writeText(buildString {
                appendLine("# 共享技能: $skillName")
                appendLine("> 来自: ${msg.from}")
                appendLine("> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                appendLine("对方共享了技能: **$skillName**")
                appendLine()
                appendLine("---")
                appendLine("使用 `skill.create $skillName <内容>` 安装此技能到本地。")
            })
            return AcpResult(true, "skill_shared", skillFile.name)
        } catch (e: Exception) {
            return AcpResult(false, "write_failed", e.message ?: "inbox write error")
        }
    }
}
