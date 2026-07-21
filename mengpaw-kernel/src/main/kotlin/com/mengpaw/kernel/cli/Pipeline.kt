// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.security.SecurityPolicy

/**
 * Execution pipeline with security checks, rate limiting, and audit trail.
 *
 * Flow: Parse → Rate Limit → Security Policy → Integrity Guard → Execute → Audit
 */
class Pipeline(
    private val interpreter: CliInterpreter = CliInterpreter(),
    private val registry: CommandRegistry = CommandRegistry(),
    private val securityPolicy: SecurityPolicy = SecurityPolicy(),
    private val maxCommandsPerSecond: Int = 30
) {
    /** Audit log of executed commands. */
    private val auditLog = mutableListOf<AuditEntry>()

    /** Timestamps of recent commands for rate limiting. */
    private val recentTimestamps = mutableListOf<Long>()

    /** Execute a command through the full security pipeline. */
    suspend fun execute(input: String, context: ExecutionContext): ExecutionResult {
        val startTime = System.currentTimeMillis()

        try {
            val trimmed = input.trim()
            if (trimmed.isBlank()) {
                return failAudit("Empty command", ErrorCodes.ERR_INVALID_INPUT, trimmed, context, startTime)
            }

            val parsed = interpreter.parse(trimmed)
            if (parsed.command.isBlank()) {
                return failAudit("Empty command", ErrorCodes.ERR_INVALID_INPUT, trimmed, context, startTime)
            }

            // VULN-FIX: Rate limiting — prevent command loop DoS
            val rateLimitError = checkRateLimit()
            if (rateLimitError != null) {
                return failAudit(rateLimitError, "ERR_RATE_LIMIT", trimmed, context, startTime)
            }

            // Security policy check
            if (!securityPolicy.isAllowed(trimmed)) {
                return failAudit(
                    "Command '${parsed.command}' is blocked by security policy",
                    ErrorCodes.ERR_PERMISSION_DENIED, trimmed, context, startTime
                )
            }

            // Integrity guard: block writes to protected paths
            val integrityError = securityPolicy.validateIntegrity(parsed.command, parsed.args)
            if (integrityError != null) {
                return failAudit(integrityError, ErrorCodes.ERR_PERMISSION_DENIED, trimmed, context, startTime)
            }

            // Find and execute
            val executor = registry.find(parsed.command)
                ?: return failAudit(
                    "Unknown command: ${parsed.command}",
                    ErrorCodes.ERR_NOT_FOUND, trimmed, context, startTime
                )

            val result = executor(parsed.args, context)
            // VULN-FIX: Audit trail — log all executions to shared static log
            // SECURITY: Sanitize output to prevent API key/token leakage in audit log
            val sanitizedOutput = com.mengpaw.kernel.security.Sanitizer.sanitize(result.output.take(200))
            val entry = AuditEntry(startTime, context.sessionId, trimmed, result.success, sanitizedOutput)
            auditLog.add(entry)
            Pipeline.addAuditEntry(entry)
            if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
            return result

        } catch (e: Exception) {
            ErrorCollector.report(e, "Pipeline.execute", context.sessionId, context.agentName)
            return failAudit(
                "Execution error: ${e.message ?: e::class.simpleName}",
                ErrorCodes.ERR_INTERNAL, input, context, startTime
            )
        }
    }

    // ── Rate Limiting ───────────────────────────────────────────────

    /**
     * Check if the current command exceeds the rate limit.
     * Uses a sliding window: max [maxCommandsPerSecond] commands per second.
     * @return An error message if rate-limited, or null if allowed.
     */
    private fun checkRateLimit(): String? {
        val now = System.currentTimeMillis()
        val windowStart = now - 1000

        // Remove timestamps outside the 1-second window
        recentTimestamps.removeAll { it < windowStart }

        if (recentTimestamps.size >= maxCommandsPerSecond) {
            return "Rate limit exceeded: max $maxCommandsPerSecond commands/second. Wait and retry."
        }

        recentTimestamps.add(now)
        return null
    }

    // ── Audit Trail ──────────────────────────────────────────────────

    /** Get recent audit entries (last N). */
    fun getAuditLog(count: Int = 50): List<AuditEntry> = auditLog.takeLast(count)

    /** Get audit entries for a specific session. */
    fun getSessionAudit(sessionId: String): List<AuditEntry> =
        auditLog.filter { it.sessionId == sessionId }

    /**
     * Clear the audit log. Only callable internally (e.g. from agent.audit command)
     * when explicitly authorized by the current security context.
     */
    fun clearAuditLog() {
        auditLog.clear()
    }

    private fun failAudit(
        error: String, errorCode: String, command: String, context: ExecutionContext, startTime: Long
    ): ExecutionResult {
        ErrorCollector.report(ErrorType.PLUGIN_ERROR, "Pipeline", "$error [$errorCode]",
            sessionId = context.sessionId, agentName = context.agentName,
            metadata = mapOf("command" to command.take(200), "errorCode" to errorCode))
        auditLog.add(AuditEntry(
            timestamp = startTime, sessionId = context.sessionId,
            command = command, success = false, output = error.take(200)
        ))
        if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
        return ExecutionResult.fail(error, errorCode = errorCode)
    }

    companion object {
        private const val MAX_AUDIT_ENTRIES = 500

        /** Shared audit log — readable by agent.audit command. */
        private val globalAuditLog = mutableListOf<AuditEntry>()

        fun addAuditEntry(entry: AuditEntry) {
            globalAuditLog.add(entry)
            if (globalAuditLog.size > MAX_AUDIT_ENTRIES) globalAuditLog.removeAt(0)
        }

        fun getGlobalAuditLog(count: Int = 50): List<AuditEntry> = globalAuditLog.takeLast(count)
    }
}

/**
 * An entry in the command execution audit trail.
 */
data class AuditEntry(
    val timestamp: Long,
    val sessionId: String,
    val command: String,
    val success: Boolean,
    val output: String
)
