package com.mengpaw.core

import com.mengpaw.core.cli.*
import com.mengpaw.core.llm.*
import com.mengpaw.core.namespace.*
import com.mengpaw.core.security.Sanitizer
import com.mengpaw.core.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the current state of the agent execution.
 */
sealed class AgentState {
    data object Idle : AgentState()
    data class Running(val task: String, val step: Int, val maxSteps: Int) : AgentState()
    data class Finished(val result: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

/**
 * Central agent engine that drives the ReAct loop:
 *   Thought → Action → Observation → Thought → ... → Final Answer
 *
 * This is the core orchestration that ties together:
 * - LLM provider (reasoning/decision making)
 * - CLI engine (action execution)
 * - Session management (conversation history)
 * - Security (sanitization)
 */
class AgentEngine(
    private val llmProvider: LlmProvider,
    private val pipeline: Pipeline = createDefaultPipeline(),
    private val sessionManager: SessionManager = SessionManager(),
    private val promptEngine: PromptEngine = PromptEngine()
) {
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _output = MutableStateFlow<String>("")
    val output: StateFlow<String> = _output.asStateFlow()

    /** Prefix-cache-optimized request builder with stable system prompt. */
    private val llmRequestBuilder = LlmRequestBuilder(systemPrompt = promptEngine.buildSystemPrompt())

    /** Cumulative cache hit tokens from LlmRequestBuilder. */
    val cacheHitTokens: Long get() = llmRequestBuilder.cumulativeCacheHitTokens

    /** Cumulative cache miss tokens from LlmRequestBuilder. */
    val cacheMissTokens: Long get() = llmRequestBuilder.cumulativeCacheMissTokens

    /**
     * Run a task through the ReAct loop.
     * @param task The user's task description
     * @param maxSteps Maximum ReAct iterations before forced stop
     */
    suspend fun run(task: String, maxSteps: Int = 50): String {
        val session = sessionManager.createSession(task)
        val context = ExecutionContext(sessionId = session.id)
        _state.value = AgentState.Running(task, 0, maxSteps)
        _output.value = ""

        // Initialize conversation — system prompt injected by buildConversation via LlmRequestBuilder
        sessionManager.addMessage(session.id, Message("user", task))

        try {
            for (step in 0 until maxSteps) {
                _state.value = AgentState.Running(task, step + 1, maxSteps)

                // 1. THINK: Get LLM response
                val conversation = buildConversation(session.id)
                val llmResponse = llmProvider.completeWithMessages(conversation)
                val sanitized = Sanitizer.sanitize(llmResponse)

                sessionManager.addMessage(session.id, Message("assistant", sanitized))
                _output.value = sanitized

                // 2. PARSE: Extract Thought/Action from LLM output
                val parsed = promptEngine.parse(sanitized)

                // 3. CHECK: Is this the final answer?
                if (parsed.isFinal) {
                    val answer = parsed.thought
                    sessionManager.addMessage(session.id, Message("assistant", answer))
                    _state.value = AgentState.Finished(answer)
                    return answer
                }

                // 4. ACT: Execute the command
                if (parsed.action != null) {
                    val command = buildCommandString(parsed.action)
                    val commandLine = "${parsed.action.name} ${parsed.action.parameters.values.joinToString(" ")}"

                    // Loop detection
                    if (promptEngine.detectLoop(commandLine)) {
                        val errorMsg = "Error: Detected command loop - '$commandLine' repeated 3+ times"
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        return errorMsg
                    }

                    // Execute
                    val result = pipeline.execute(commandLine, context)
                    val observation = if (result.success) result.output else "Error: ${result.error}"

                    // Add observation to conversation
                    val observationEntry = "Command: $commandLine\nResult: $observation"
                    sessionManager.addMessage(session.id, Message("assistant", observationEntry))
                }
            }

            // Max steps reached
            val msg = "Max steps ($maxSteps) reached without final answer"
            sessionManager.addMessage(session.id, Message("assistant", msg))
            _state.value = AgentState.Finished(msg)
            return msg
        } catch (e: Exception) {
            val errorMsg = "Agent error: ${e.message ?: e::class.simpleName}"
            sessionManager.addMessage(session.id, Message("assistant", errorMsg))
            _state.value = AgentState.Error(errorMsg)
            return errorMsg
        }
    }

    /**
     * Stop the current agent execution.
     */
    fun stop() {
        _state.value = AgentState.Idle
    }

    private fun buildConversation(sessionId: String): List<Map<String, String>> {
        sessionManager.compressIfNeeded()
        val history = sessionManager.getStructuredHistory(sessionId)
        // If no system message at index 0, inject via LlmRequestBuilder for prefix cache
        if (history.isEmpty() || history[0]["role"] != "system") {
            return llmRequestBuilder.buildMessages(history)
        }
        return history
    }

    private fun buildCommandString(action: ToolCall): String {
        val params = action.parameters.entries.joinToString(" ") { (k, v) -> "--$k $v" }
        return "${action.name} $params"
    }

    companion object {
        fun createDefaultPipeline(): Pipeline {
            val registry = CommandRegistry()
            registry.registerNamespace("fs", FsExecutor.commands)
            registry.registerNamespace("ui", UiExecutor.commands)
            registry.registerNamespace("proc", ProcExecutor.commands)
            registry.registerNamespace("net", NetExecutor.commands)
            registry.registerNamespace("self", SelfExecutor.commands)
            registry.registerNamespace("memory", MemoryExecutor.commands)
            registry.registerNamespace("skill", SkillExecutor.commands)
            return Pipeline(registry = registry)
        }
    }
}
