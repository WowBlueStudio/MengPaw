package com.mengpaw.core.security

/**
 * Sanitizer that redacts sensitive information (API keys, tokens) from logs and outputs.
 */
object Sanitizer {
    private val patterns = listOf(
        Regex("sk-[a-zA-Z0-9_-]{20,60}"),                  // OpenAI (sk-proj-... or sk-...)
        Regex("sk-ant-[a-zA-Z0-9_-]{32,}"),                // Anthropic
        Regex("AIza[0-9A-Za-z_-]{35}"),                     // Google
        Regex("(?i)bearer\\s+[a-zA-Z0-9\\-_\\.]{20,}"),   // Bearer tokens
        Regex("[A-Za-z0-9+/]{40,}={0,2}")                  // Base64 heuristic
    )

    /**
     * Redact sensitive patterns from text.
     */
    fun sanitize(text: String): String {
        var result = text
        for (pattern in patterns) {
            result = pattern.replace(result) { match ->
                "***REDACTED_${match.value.take(4)}***"
            }
        }
        return result
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
