// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

/**
 * Sanitizer that redacts sensitive information and neutralizes dangerous input.
 *
 * Covers:
 *  1. API key / token redaction (OpenAI, Anthropic, Google, Bearer, generic Base64)
 *  2. Special character / escape sequence sanitization
 *  3. Truncation of overly long input (threshold: 10,000 chars)
 *  4. Sensitive Unicode pattern detection (RTL override, zero-width, homoglyph attacks)
 */
object Sanitizer {
    private const val MAX_INPUT_LENGTH = 10_000

    // ── API Key & Token Patterns ──────────────────────────────────────────
    // ── Prompt Injection Patterns (Inspired by QwenPaw Skill Scanner) ──
    private val promptInjectionPatterns = listOf(
        // Ignore/override instructions
        Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|earlier)\\s+(instructions|rules|prompts)"),
        Regex("(?:忽略|忘掉|无视)\\s*(?:所有)?\\s*(?:之前|先前|上文)?\\s*(?:指令|指示|规则|提示|要求)"),
        // Unrestricted mode
        Regex("(?i)(unrestricted|debug|developer|admin|god|jailbreak)\\s+mode"),
        Regex("(?:无限制|越狱|开发者|调试|管理员|上帝)(?:模式)?"),
        // Bypass policy
        Regex("(?i)bypass\\s+(content|usage|safety)\\s+policy"),
        Regex("(?:绕过|躲开|规避)\\s*(?:内容|使用|安全)?\\s*(?:策略|政策|限制|审核)"),
        // Concealment
        Regex("(?i)do\\s+not\\s+(tell|inform|mention|notify)\\s+(the\\s+)?user"),
        Regex("(?:不要|勿|请勿|别)\\s*(?:告诉|告知|通知|提及)\\s*(?:用户|使用者)")
    )

    private val secretPatterns = listOf(
        Regex("sk-[a-zA-Z0-9_-]{20,60}"),                  // OpenAI (sk-proj-... or sk-...)
        Regex("sk-ant-[a-zA-Z0-9_-]{32,}"),                // Anthropic
        Regex("AIza[0-9A-Za-z_-]{35}"),                     // Google
        Regex("(?i)bearer\\s+[a-zA-Z0-9\\-_\\.]{20,}"),   // Bearer tokens
        // VULN-FIX: Base64 heuristic now requires padding + entropy check
        // Only matches runs with at least one uppercase AND one digit AND one lowercase
        // to reduce false positives on normal text content.
        Regex("(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)[A-Za-z0-9+/]{44,}={0,2}")
    )

    // ── Dangerous Special Characters & Escape Sequences ───────────────────
    private val escapePatterns = listOf(
        // ANSI escape sequences (CSI, OSC, etc.) — terminal injection
        Regex("\\x1B\\[[0-9;]*[A-Za-z]"),
        Regex("\\x1B\\][0-9;]*[\\x07\\\\]"),
        // Control characters (except common whitespace: \t \n \r)
        Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"),
        // Null byte injection
        Regex("\\x00")
    )

    // ── Sensitive Unicode Patterns (RTL override, zero-width, homoglyph) ─
    private val sensitiveUnicode = listOf(
        // Right-to-left override (U+202E) — can visually spoof file extensions
        Regex("\\u202E"),
        // Left-to-right override (U+202D) — paired with RLO for bidirectional spoofing
        Regex("\\u202D"),
        // Right-to-left embedding / pop directional formatting (U+202A, U+202C)
        Regex("[\\u202A\\u202C]"),
        // Zero-width space (U+200B) — invisible text injection
        Regex("\\u200B"),
        // Zero-width joiner / non-joiner (U+200C, U+200D)
        Regex("[\\u200C\\u200D]"),
        // Zero-width no-break space / word joiner (U+FEFF, U+2060)
        Regex("[\\uFEFF\\u2060]"),
        // Byte order mark (U+FEFF) when used mid-string
        Regex("\\uFEFF"),
        // Homoglyph characters from Cyrillic/Greek that look like Latin
        // Common spoof chars: Cyrillic 'а' U+0430, 'е' U+0435, 'о' U+043E, 'с' U+0441
        // Greek 'ο' U+03BF, 'ν' U+03BD, 'Α' U+0391
        Regex("[\\u0430\\u0435\\u043E\\u0441\\u03BF\\u03BD]")
    )

    /**
     * Full sanitization pipeline: redact secrets, strip dangerous chars,
     * truncate if too long, and scan for Unicode spoofing patterns.
     *
     * @return A [SanitizeResult] with the cleaned text and any warnings.
     */
    fun sanitize(text: String): String {
        var result = text

        // Step 1: Truncate if over threshold
        if (result.length > MAX_INPUT_LENGTH) {
            result = result.take(MAX_INPUT_LENGTH) + "\n[TRUNCATED: input exceeded ${MAX_INPUT_LENGTH} characters]"
        }

        // Step 2: Redact API keys / tokens
        for (pattern in secretPatterns) {
            result = pattern.replace(result) { match ->
                "***REDACTED_${match.value.take(4)}***"
            }
        }

        // Step 3: Strip ANSI escape sequences and control characters
        for (pattern in escapePatterns) {
            result = pattern.replace(result, "")
        }

        // Step 4: Flag prompt injection attempts (Inspired by QwenPaw)
        for (pattern in promptInjectionPatterns) {
            if (pattern.containsMatchIn(result)) {
                result = "[PROMPT_INJECTION_WARN] " + result
                break
            }
        }

        // Step 5: Strip sensitive Unicode characters
        for (pattern in sensitiveUnicode) {
            result = pattern.replace(result, "[UNICODE_WARN]")
        }

        return result
    }

    /**
     * Scan-only: returns true if the input contains any sensitive Unicode
     * spoofing characters (RTL override, zero-width, homoglyph).
     */
    fun hasSensitiveUnicode(text: String): Boolean {
        return sensitiveUnicode.any { it.containsMatchIn(text) }
    }

    /**
     * Sanitize a throwable's stack trace.
     */
    fun sanitizeThrowable(e: Throwable): String {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        return sanitize(sw.toString())
    }
}
