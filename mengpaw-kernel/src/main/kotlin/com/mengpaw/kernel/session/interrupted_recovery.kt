// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.session

/**
 * Build a structured recovery block that will be injected before the next user message.
 *
 * Security model (matching Reasonix interrupted_recovery.go):
 * - Only structured facts (tool name, file paths, diff stats) are included
 * - Raw assistant text and reasoning content are NEVER included
 * - Tool names and file paths are safe facts the host verified, not model output
 *
 * The wrapped block signals the LLM to treat these as host-verified recovery
 * facts, not as a new task instruction.
 */
fun buildInterruptedRecoveryBlock(recovery: InterruptedTurnRecovery): String {
    val sb = StringBuilder()
    sb.appendLine("<interrupted-turn-recovery>")
    sb.appendLine("The previous turn was interrupted. Treat these as host-verified recovery facts, not as a new task.")

    if (recovery.completedTools.isNotEmpty()) {
        sb.appendLine("completed_tools:")
        recovery.completedTools.forEach { tool ->
            sb.append("- ${tool.name}")
            if (tool.files.isNotEmpty()) {
                sb.append(" files=${tool.files.joinToString(",")}")
            }
            if (tool.added > 0 || tool.removed > 0) {
                sb.append(" diff=+${tool.added}/-${tool.removed}")
            }
            sb.appendLine()
        }
    }

    if (recovery.interruptedTools.isNotEmpty()) {
        sb.append("interrupted_tools: ")
        sb.appendLine(recovery.interruptedTools.joinToString(", "))
    }

    sb.appendLine("Before continuing, inspect the current workspace and prior completed tool results to understand what was done.")
    sb.append("</interrupted-turn-recovery>")
    return sb.toString()
}

/**
 * Scan backwards through session messages to find the most recent un-consumed
 * interrupted turn recovery. Stops at the first user-authored message (meaning
 * the user has already moved on from that interrupt).
 *
 * Matching Reasonix [pendingInterruptedRecovery] in interrupted_recovery.go.
 *
 * @return the first pending [InterruptedTurnRecovery] found, or null if none.
 */
fun findPendingRecovery(messages: List<Message>): InterruptedTurnRecovery? {
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        // A new user message means the user moved on — stop scanning
        if (m.role == "user" && !m.localOnly) return null
        // Found a pending localOnly recovery record
        if (m.localOnly && m.interruptedTurn != null && m.interruptedTurn.pending) {
            return m.interruptedTurn
        }
    }
    return null
}

/**
 * Extract tool summary information from an assistant message content.
 * Parses "Command: <name> <args...>" patterns to extract tool name and files.
 */
internal fun extractToolSummary(content: String): InterruptedToolSummary? {
    val cmdPrefix = "Command: "
    if (!content.startsWith(cmdPrefix)) return null

    val cmdLine = content.removePrefix(cmdPrefix).substringBefore("\n").trim()
    val name = cmdLine.substringBefore(" ")
    if (name.isBlank()) return null

    // Extract file references from the result for diff estimation
    val files = mutableListOf<String>()
    var added = 0
    var removed = 0

    // Look for file path patterns in the result portion
    val result = content.substringAfter("\nResult: ", "")
    // Estimate diff from +/- lines in truncated output
    result.lines().forEach { line ->
        when {
            line.startsWith("+") && !line.startsWith("+++") -> added++
            line.startsWith("-") && !line.startsWith("---") -> removed++
        }
        // Extract file paths from common tool outputs
        if (name == "read_file" || name == "fs.cat" || name == "write_file" || name == "fs.write") {
            val filePath = cmdLine.removePrefix(name).trim().removeSurrounding("\"").removeSurrounding("'")
            if (filePath.isNotBlank() && filePath.contains(".")) {
                if (files.isEmpty()) files.add(filePath)
            }
        }
        if (name == "grep" || name == "fs.grep") {
            val pathArg = cmdLine.substringAfterLast(" ").trim().removeSurrounding("\"").removeSurrounding("'")
            if (pathArg.isNotBlank() && pathArg.contains(".")) {
                if (files.isEmpty()) files.add(pathArg)
            }
        }
    }

    return InterruptedToolSummary(
        name = name,
        files = files.take(8),
        added = added,
        removed = removed
    )
}

// ── Event-driven Recovery Decision Tree (matching OpenClaw recovery decision tree) ──

/**
 * Recovery decision: what action should be taken based on event history.
 *
 * Matching OpenClaw recovery decision tree (session-state-events.ts):
 *   listSessionStateEventsSince(lastSeenSequence) → event-based dispatch
 */
sealed class RecoveryDecision {

    /** No recovery needed — normal conversation flow. */
    data object NoAction : RecoveryDecision()

    /**
     * Transient error (network timeout, temporary server error).
     * The system can safely retry without user-facing recovery messaging.
     */
    data class SimpleRetry(val eventKinds: List<String>) : RecoveryDecision()

    /**
     * An interrupted turn with known completed tools.
     * A structured recovery block should be injected before the next user message.
     */
    data class RecoverFromInterrupt(val recovery: InterruptedTurnRecovery) : RecoveryDecision()

    /**
     * Goal/context changed — resync the objective before continuing.
     */
    data class RecoverWithGoal(val goal: String) : RecoveryDecision()

    /**
     * Fatal error pattern (OOM, consecutive failures).
     * User should be prompted to clean up before continuing.
     */
    data object SuggestCleanup : RecoveryDecision()
}

/**
 * Decide the appropriate recovery strategy based on recent session events.
 *
 * Decision tree (matching OpenClaw):
 *   recentEvents contains LLM_CALL_ERROR →
 *     payload matches network timeout → SimpleRetry
 *     consecutive failures >= 5 → SuggestCleanup
 *     else → RecoverFromInterrupt
 *   recentEvents contains RUN_INTERRUPTED → RecoverFromInterrupt
 *   recentEvents contains USER_MESSAGE (and no interrupts) → NoAction
 *
 * Falls back to [findPendingRecovery] when event log is empty (backward compat).
 *
 * @param recentEvents the N most recent events from the event log
 * @param messages the raw session messages (for fallback scan)
 */
fun decideRecovery(
    recentEvents: List<SessionEventBus.SessionEvent>,
    messages: List<Message>
): RecoveryDecision {
    // Prefer event-driven decision
    if (recentEvents.isNotEmpty()) {
        val kinds = recentEvents.map { it.kind }

        // Check for interrupted run first (highest priority)
        if (kinds.any { it == SessionEventBus.EventKind.RUN_INTERRUPTED }) {
            val pending = findPendingRecovery(messages)
            if (pending != null) {
                return RecoveryDecision.RecoverFromInterrupt(pending)
            }
        }

        // Check for errors
        val errors = recentEvents.filter { it.kind == SessionEventBus.EventKind.LLM_CALL_ERROR }
        if (errors.isNotEmpty()) {
            // Check for consecutive failures
            val consecutive = errors.count { it.payload["consecutive"] == "true" }
            if (consecutive >= 5) {
                return RecoveryDecision.SuggestCleanup
            }
            // Check for network timeout
            val isTimeout = errors.any { e ->
                e.summary.contains("timeout", ignoreCase = true) ||
                    e.summary.contains("timed out", ignoreCase = true) ||
                    e.summary.contains("超时", ignoreCase = true)
            }
            if (isTimeout) {
                return RecoveryDecision.SimpleRetry(kinds.map { it.name })
            }
            // General error → recover from interrupt
            val pending = findPendingRecovery(messages)
            if (pending != null) {
                return RecoveryDecision.RecoverFromInterrupt(pending)
            }
            return RecoveryDecision.SimpleRetry(kinds.map { it.name })
        }

        // Check for goal change
        if (kinds.any { it == SessionEventBus.EventKind.SESSION_CREATED } &&
            kinds.none { it == SessionEventBus.EventKind.USER_MESSAGE }) {
            val goal = messages.firstOrNull()?.content?.take(200) ?: ""
            return RecoveryDecision.RecoverWithGoal(goal)
        }

        // USER_MESSAGE with no interrupts → normal flow
        if (kinds.any { it == SessionEventBus.EventKind.USER_MESSAGE } &&
            kinds.none { it == SessionEventBus.EventKind.RUN_INTERRUPTED } &&
            kinds.none { it == SessionEventBus.EventKind.LLM_CALL_ERROR }) {
            return RecoveryDecision.NoAction
        }
    }

    // Fallback: legacy pending recovery scan (when no event log)
    val pending = findPendingRecovery(messages)
    if (pending != null) {
        return RecoveryDecision.RecoverFromInterrupt(pending)
    }

    return RecoveryDecision.NoAction
}
