// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider
import com.mengpaw.kernel.security.SecurityPolicy

/**
 * Execution pipeline with security checks, rate limiting, and audit trail.
 *
 * Flow: Parse → Result Cache → Rate Limit → Security Policy → Integrity Guard → Execute → Audit
 * （缓存命中跳过限流/安全/执行，但保留 audit 轨迹）
 */
class Pipeline(
    private val interpreter: CliInterpreter = CliInterpreter(),
    private val registry: CommandRegistry = CommandRegistry(),
    private val securityPolicy: SecurityPolicy = SecurityPolicy(),
    private val maxCommandsPerSecond: Int = 30,
    /** 只读命令结果缓存（白名单 + 短 TTL）；null = 关闭。 */
    private val resultCache: CommandResultCache? = null
) {
    /** Integrity provider for path-level protection; set after construction for Android. */
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider
    private val pipelineLock = Any()
    /** Audit log of executed commands. Guarded by [pipelineLock]. */
    private val auditLog = mutableListOf<AuditEntry>()

    /** Timestamps of recent commands for rate limiting. Guarded by [pipelineLock]. */
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

            // ── Result cache: 白名单只读命令命中直接返回（跳过限流/安全/执行）──
            val cacheable = resultCache != null && parsed.command in CommandResultCache.CACHEABLE
            if (cacheable) {
                val cacheKey = resultCache!!.keyFor(parsed.command, parsed.args, context.agentName, context.sessionId)
                val cached = resultCache.get(cacheKey)
                if (cached != null) {
                    synchronized(pipelineLock) {
                        auditLog.add(AuditEntry(startTime, context.sessionId, trimmed, true, "(cache hit)"))
                        if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
                    }
                    return cached
                }
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
            val integrityError = integrityProvider.validateCommand(parsed.command, parsed.args)
            if (integrityError != null) {
                return failAudit(integrityError, ErrorCodes.ERR_PERMISSION_DENIED, trimmed, context, startTime)
            }

            // Find and execute
            val executor = registry.find(parsed.command)
                ?: return failAudit(
                    "Unknown command: ${parsed.command}",
                    ErrorCodes.ERR_NOT_FOUND, trimmed, context, startTime
                )

            // P0-3(自检报告): 框架层参数签名预校验 — 必选参数不足即返回统一
            // "期望 usage, 收到 N 参" 错误, 模型据此收敛重试 (此前错误文本散在各 handler,
            // 无期望/收到对比, 模型盲猜重试浪费 token)
            val signatureError = registry.validateArgs(parsed.command, parsed.args)
            if (signatureError != null) {
                return failAudit(signatureError, ErrorCodes.ERR_INVALID_INPUT, trimmed, context, startTime)
            }

            val result = executor(parsed.args, context)
            // 非白名单命令（含写命令 agent.write/fs.write 等）成功 → 清空缓存,
            // 防"写入→立即读"命中写前旧快照（P1 修复: 写后读陈旧）
            // 注: resultCache 可能为 null（未启用缓存的 Pipeline）— 双重判空
            if (resultCache != null && !cacheable && result.success) {
                resultCache!!.clear()
            }
            // 只读命令成功结果写入缓存（同会话键）
            if (cacheable && result.success) {
                resultCache!!.put(
                    resultCache.keyFor(parsed.command, parsed.args, context.agentName, context.sessionId),
                    result
                )
            }
            // VULN-FIX: Audit trail — log all executions to shared static log
            // SECURITY: Sanitize output to prevent API key/token leakage in audit log
            val sanitizedOutput = com.mengpaw.kernel.security.Sanitizer.sanitize(result.output.take(200))
            val entry = AuditEntry(startTime, context.sessionId, trimmed, result.success, sanitizedOutput)
            synchronized(pipelineLock) {
                auditLog.add(entry)
                if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
            }
            Pipeline.addAuditEntry(entry)
            return result

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 取消契约: 用户 stop() 不吞成 "Execution error" (同 AgentEngine.kt:804 先例)
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

        synchronized(pipelineLock) {
            // Remove timestamps outside the 1-second window
            recentTimestamps.removeAll { it < windowStart }

            if (recentTimestamps.size >= maxCommandsPerSecond) {
                return "Rate limit exceeded: max $maxCommandsPerSecond commands/second. Wait and retry."
            }

            recentTimestamps.add(now)
        }
        return null
    }

    // ── Audit Trail ──────────────────────────────────────────────────

    /** Get recent audit entries (last N). */
    fun getAuditLog(count: Int = 50): List<AuditEntry> =
        synchronized(pipelineLock) { auditLog.takeLast(count) }

    /** Get audit entries for a specific session. */
    fun getSessionAudit(sessionId: String): List<AuditEntry> =
        synchronized(pipelineLock) { auditLog.filter { it.sessionId == sessionId } }

    /**
     * Clear the audit log. Only callable internally (e.g. from agent.audit command)
     * when explicitly authorized by the current security context.
     */
    fun clearAuditLog() {
        synchronized(pipelineLock) { auditLog.clear() }
    }

    private fun failAudit(
        error: String, errorCode: String, command: String, context: ExecutionContext, startTime: Long
    ): ExecutionResult {
        ErrorCollector.report(ErrorType.PLUGIN_ERROR, "Pipeline", "$error [$errorCode]",
            sessionId = context.sessionId, agentName = context.agentName,
            metadata = mapOf("command" to command.take(200), "errorCode" to errorCode))
        synchronized(pipelineLock) {
            auditLog.add(AuditEntry(
                timestamp = startTime, sessionId = context.sessionId,
                command = command, success = false, output = error.take(200)
            ))
            if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
        }
        return ExecutionResult.fail(error, errorCode = errorCode)
    }

    companion object {
        private const val MAX_AUDIT_ENTRIES = 500
        private val globalAuditLock = Any()

        /** Shared audit log — readable by agent.audit command. Guarded by [globalAuditLock]. */
        private val globalAuditLog = mutableListOf<AuditEntry>()

        fun addAuditEntry(entry: AuditEntry) {
            synchronized(globalAuditLock) {
                globalAuditLog.add(entry)
                if (globalAuditLog.size > MAX_AUDIT_ENTRIES) globalAuditLog.removeAt(0)
            }
        }

        fun getGlobalAuditLog(count: Int = 50): List<AuditEntry> =
            synchronized(globalAuditLock) { globalAuditLog.takeLast(count) }
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
