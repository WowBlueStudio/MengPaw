// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 部落消息命令组 — 从 TribePlugin 拆分。
 * 共享记忆 / 群聊广播 / 全员讨论 (memo/chat/discuss)。
 *
 * 依赖通过构造参数注入; 全局依赖 (llmProvider/isRunning/acpServer/agentId)
 * 读 [TribePlugin] companion。
 */
internal class TribeMessagingCommands(
    private val delegateEngine: TribeDelegateEngine,
    private val discoverMembers: () -> List<TeamMember>
) {

    // ─────────────────────────────────────────────────────────────
    // 共享记忆
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdMemo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // --compact: 手动压缩最旧的记忆为摘要
        if (args.contains("--compact")) {
            val llm = TribePlugin.llmProvider ?: return ExecutionResult.fail(
                "LLM 未配置，无法压缩。请先在设置中配置 API Key。", errorCode = ErrorCodes.ERR_INTERNAL)
            val deleted = TribeMemoStore.compactOldest(llm)
            return ExecutionResult.ok("✅ 已压缩 ${deleted} 条记忆为摘要。当前剩余: ${TribeMemoStore.count()} 条")
        }

        // 无参数: 列出最近 10 条（含去重/压缩后的记忆）
        if (args.isEmpty()) {
            val memos = TribeMemoStore.listRecent(10)
            if (memos.isEmpty()) return ExecutionResult.ok("(无团队共享记忆)")
            return ExecutionResult.ok(memos.joinToString("\n---\n") {
                try { it.readText().take(300) } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.memo"); "(read error)" }
            })
        }
        val content = args.joinToString(" ")
        return try {
            when (val result = TribeMemoStore.publish(content, ctx.sessionId)) {
                is TribeMemoStore.PublishResult.Duplicate -> ExecutionResult.ok("🔁 共享记忆内容重复（指纹 ${result.hash.take(8)}），已跳过。")
                is TribeMemoStore.PublishResult.Published -> {
                    // 如果 ACP 运行中，广播 SHARE_MEMORY
                    if (TribePlugin.isRunning) {
                        val server = TribePlugin.acpServer
                        if (server != null) {
                            val msg = com.mengpaw.kernel.acp.AcpMessage.shareMemory(TribePlugin.agentId, "*", content.take(200))
                            // P2 修复: 空 catch 吞异常无日志 — 补 ErrorCollector (广播失败不阻断发布, 但要可审计)
                            try { server.sendViaTransport(msg) } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.shareMemoryBroadcast") }
                        }
                    }
                    // 超阈值自动压缩
                    val compacted = TribeMemoStore.compactIfNeeded(TribePlugin.llmProvider)
                    val compactNote = if (compacted > 0) "\n📦 已自动压缩 $compacted 条旧记忆为摘要。" else ""
                    ExecutionResult.ok("✅ 共享记忆已发布。${TribeMemoStore.count()} 条$compactNote")
                }
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.memo")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 聊天 / 讨论
    // ─────────────────────────────────────────────────────────────

    /** 部落广播 — 向所有团队成员群聊消息。 */
    suspend fun cmdChat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.chat <message>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val message = args.joinToString(" ")

        // 写本地团队共享收件箱
        val inbox = File(DataPaths.TEAM_INBOX).also { it.mkdirs() }
        val chatFile = File(inbox, "chat_${System.currentTimeMillis()}.md")
        try {
            chatFile.writeText("""
# 部落广播
- 来自: ${ctx.sessionId}
- 时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}

$message
""".trimIndent())
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.chat")
        }

        // ACP 广播给所有团队成员（在线时）
        var broadcastCount = 0
        if (TribePlugin.isRunning) {
            val server = TribePlugin.acpServer
            if (server != null) {
                val msg = com.mengpaw.kernel.acp.AcpMessage.tribeChat(TribePlugin.agentId, "*", message)
                // P2 修复: 空 catch 吞异常无日志 — 广播失败要可审计 (broadcastCount 维持 0)
                try { server.sendViaTransport(msg); broadcastCount = 1 }
                catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.chatBroadcast") }
            }
        }
        val note = if (broadcastCount > 0) "，已广播到局域网" else ""
        return ExecutionResult.ok("📢 部落广播已发布$note。成员可在 team/inbox/ 查看。")
    }

    /** 部落讨论 — 让每个团队成员就主题发言。 */
    suspend fun cmdDiscuss(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.discuss <topic>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val topic = args.joinToString(" ")
        val members = discoverMembers()
        if (members.isEmpty()) return ExecutionResult.fail(
            "团队为空。先用 tribe.discover --lan 自动组队。", errorCode = ErrorCodes.ERR_NOT_FOUND)

        // 并行向每个成员委派发言（P1, 60s 超时），失败不中断
        val contributions = coroutineScope {
            members.map { m ->
                async(Dispatchers.IO) {
                    val task = TribeTask(
                        title = "讨论发言: $topic",
                        description = "请就以下主题发表你的观点（你是角色: ${m.role}）:\n\n$topic",
                        priority = TaskPriority.P1,
                        fromAgent = ctx.sessionId.take(8),
                        toAgent = m.id,
                        timeoutMs = 60_000L,
                        delegateMode = DelegateMode.AUTO
                    )
                    val result = delegateEngine.delegate(task, m.id, m.name)
                    m.name to result
                }
            }.awaitAll()
        }

        val sb = StringBuilder("## 💬 部落讨论: $topic\n\n")
        contributions.forEach { (name, result) ->
            if (result.success) {
                sb.appendLine("### ${name}\n${result.output.take(500)}\n")
            } else {
                sb.appendLine("### ${name}\n⏰ (未发言: ${result.error ?: "超时"})\n")
            }
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }
}
