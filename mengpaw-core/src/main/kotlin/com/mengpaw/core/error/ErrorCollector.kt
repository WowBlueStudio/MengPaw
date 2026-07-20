// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.error

import com.mengpaw.core.DataPaths
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Error type classification for structured reporting.
 */
enum class ErrorType {
    /** File read/write failure (IO exceptions, disk full, permission denied). */
    IO_ERROR,
    /** Null-pointer dereference or !! crash. */
    NPE,
    /** Agent tool call returned non-success ExecutionResult. */
    TOOL_CALL_FAILED,
    /** LLM output parsing failed — malformed Thought/Action/Final Answer. */
    SYNTAX_ERROR,
    /** Agent command loop detected (same command repeated 3+ times). */
    LOOP_DETECTED,
    /** AgentEngine.run() or runWithPlan() caught an unexpected exception. */
    AGENT_CRASH,
    /** Unhandled exception caught by Thread.uncaughtExceptionHandler. */
    APP_CRASH,
    /** Plugin execution error (unknown command, handler threw, etc.). */
    PLUGIN_ERROR,
    /** HTTP / network request failure. */
    NETWORK_ERROR,
    /** Fallback for anything not classified above. */
    UNKNOWN
}

/**
 * A single error record collected by [ErrorCollector].
 */
data class ErrorEntry(
    val id: String,
    val timestamp: Long,
    val type: ErrorType,
    /** Human-readable source: "AgentEngine", "FsPlugin.cat", "Pipeline" */
    val source: String,
    val message: String,
    val stackTrace: String = "",
    val sessionId: String? = null,
    val agentName: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /** Whether this entry has been uploaded to the remote server. */
    var reported: Boolean = false
) {
    /** One-line summary for list display. */
    val summary: String get() = "[${type.name}] $source: $message".take(120)
}

/**
 * Global error collector — lightweight, no Android dependency.
 *
 * ## Design
 * - In-memory ring buffer of the most recent 200 entries.
 * - Appends JSON-lines to [DataPaths.ERROR_LOG]/errors.jsonl for persistence.
 * - All methods are thread-safe and never throw to the caller.
 *
 * ## Usage
 * ```kotlin
 * ErrorCollector.report(ErrorType.IO_ERROR, "FsPlugin.cat", "File not readable", exception)
 * ErrorCollector.report(ErrorType.TOOL_CALL_FAILED, "AgentEngine", "fs.cat failed",
 *     sessionId = "sess-123", agentName = "MengPaw")
 * // Agent queries:
 * val recent = ErrorCollector.list(20)
 * val detail = ErrorCollector.show("err_001")
 * ErrorCollector.exportJson()
 * ```
 */
object ErrorCollector {

    private const val MAX_MEMORY = 200
    private const val ERROR_FILE = "errors.jsonl"

    private val buffer = ConcurrentLinkedQueue<ErrorEntry>()
    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private var nextId = 0L
    private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    private var initialized = false

    // ── Initialization ──────────────────────────────────────────────────

    /**
     * Initialize the collector: create the log directory and register the
     * global uncaught-exception handler. Safe to call multiple times.
     */
    @Synchronized
    fun init() {
        if (initialized) return
        initialized = true

        // Ensure storage directory exists
        try { File(logDir).mkdirs() } catch (_: Exception) { }

        // Load existing entries from disk (up to MAX_MEMORY)
        try {
            val file = logFile()
            if (file.exists()) {
                file.readLines().takeLast(MAX_MEMORY).forEach { line ->
                    try {
                        val entry = json.decodeFromString<ErrorEntry>(line)
                        buffer.add(entry)
                        val seq = entry.id.removePrefix("err_").toLongOrNull()
                        if (seq != null && seq >= nextId) nextId = seq + 1
                    } catch (_: Exception) { /* skip corrupted lines */ }
                }
            }
        } catch (_: Exception) { }

        // Register crash handler — delegate to original after capture
        originalUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                report(
                    type = ErrorType.APP_CRASH,
                    source = "Thread:${thread.name}",
                    message = throwable.message ?: "(no message)",
                    throwable = throwable
                )
            } catch (_: Exception) { /* must not throw */ }
            originalUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Report an error to the in-memory buffer and persist to disk.
     * This method never throws — internal failures are silently swallowed.
     */
    fun report(
        type: ErrorType,
        source: String,
        message: String,
        throwable: Throwable? = null,
        sessionId: String? = null,
        agentName: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): String {
        return try {
            val id = "err_${nextId++}"
            val entry = ErrorEntry(
                id = id,
                timestamp = System.currentTimeMillis(),
                type = type,
                source = source,
                message = message.take(500),
                stackTrace = throwable?.let { stackTraceToString(it) } ?: "",
                sessionId = sessionId,
                agentName = agentName,
                metadata = metadata
            )
            // Ring buffer eviction
            while (buffer.size >= MAX_MEMORY) buffer.poll()
            buffer.add(entry)
            // Append to disk
            appendToFile(entry)
            id
        } catch (_: Exception) {
            "" // silently fail — collector must never crash
        }
    }

    /**
     * Convenience: report from a caught exception with inferred ErrorType.
     */
    fun report(
        throwable: Throwable,
        source: String,
        sessionId: String? = null,
        agentName: String? = null
    ): String {
        val type = when (throwable) {
            is java.io.IOException -> ErrorType.IO_ERROR
            is NullPointerException -> ErrorType.NPE
            else -> ErrorType.UNKNOWN
        }
        return report(type, source, throwable.message ?: "(no message)", throwable, sessionId, agentName)
    }

    /**
     * Return the most recent [n] error entries (newest first).
     */
    fun list(n: Int = 50): List<ErrorEntry> {
        return try {
            buffer.toList().takeLast(n).reversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Look up a single error entry by its id.
     */
    fun show(id: String): ErrorEntry? {
        return try {
            buffer.find { it.id == id }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clear all in-memory entries and delete the local log file.
     */
    fun clear() {
        try {
            buffer.clear()
            nextId = 0
            logFile().delete()
        } catch (_: Exception) { }
    }

    /**
     * Export all collected errors as a JSON array string.
     */
    fun exportJson(): String {
        return try {
            val entries = buffer.toList()
            json.encodeToString(entries)
        } catch (_: Exception) {
            "[]"
        }
    }

    /**
     * Mark entries as reported (after successful upload).
     */
    fun markReported(ids: List<String>) {
        try {
            buffer.forEach { entry ->
                if (entry.id in ids) entry.reported = true
            }
        } catch (_: Exception) { }
    }

    /**
     * Return entries not yet reported, oldest first (for batch upload).
     */
    fun pendingUploads(): List<ErrorEntry> {
        return try {
            buffer.filter { !it.reported }.sortedBy { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private val logDir: String get() = DataPaths.ERROR_LOG

    private fun logFile(): File = File(logDir, ERROR_FILE)

    private fun appendToFile(entry: ErrorEntry) {
        try {
            val file = logFile()
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(entry) + "\n")
        } catch (_: Exception) { }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        return try {
            StringWriter().use { sw ->
                PrintWriter(sw).use { pw -> throwable.printStackTrace(pw); sw.toString() }
            }
        } catch (_: Exception) {
            throwable.message ?: ""
        }
    }
}
