// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.agent

/**
 * Middleware hooks for the agent ReAct loop.
 * Each hook can intercept or transform data at a specific lifecycle point.
 * Implement as `fun interface` for zero-allocation SAM conversion in Kotlin.
 *
 * Inspired by QwenPaw's middleware pattern (Apache 2.0), adapted for Android/Kotlin.
 */
fun interface AgentMiddleware {
    /** Called before the system prompt is sent. Return the (possibly modified) prompt. */
    fun onSystemPrompt(prompt: String, agentName: String): String

    companion object {
        val NoOp = AgentMiddleware { prompt, _ -> prompt }

        /** Compose a list of middlewares into a chain. */
        fun chain(vararg mws: AgentMiddleware): AgentMiddleware = AgentMiddleware { prompt, name ->
            var p = prompt
            for (mw in mws) p = mw.onSystemPrompt(p, name)
            p
        }
    }
}

/** Middleware hooks for post-LLM-call processing. */
fun interface PostCallMiddleware {
    /**
     * Called after every LLM call. Can modify the response, trigger side-effects,
     * or signal context folding. Return the (possibly modified) response text.
     * Runs inline on the agent loop thread — keep synchronous for low latency.
     */
    fun onPostCall(
        response: String,
        step: Int,
        totalChars: Int,
        estimatedTokens: Int
    ): PostCallResult

    companion object {
        val NoOp = PostCallMiddleware { response, _, _, _ -> PostCallResult(response) }
    }
}

data class PostCallResult(
    val text: String,
    val shouldFold: Boolean = false,
    val foldReason: String? = null
)
