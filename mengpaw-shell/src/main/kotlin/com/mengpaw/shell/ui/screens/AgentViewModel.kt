package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.core.AgentEngine
import com.mengpaw.core.AgentState
import com.mengpaw.core.llm.ProviderInfo
import com.mengpaw.core.llm.ProviderType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the main agent chat screen.
 * Manages the connection between UI and the AgentEngine.
 */
class AgentViewModel : ViewModel() {

    // Currently using simulated LLM; swap to RemoteApi when API key is configured
    private val llmProvider = SimulatedLlmProvider()
    private val agentEngine = AgentEngine(llmProvider)

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(
        listOf(ChatMessageUi.System("Agent is ready. Describe the task you want to accomplish."))
    )
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _inputEnabled = MutableStateFlow(true)
    val inputEnabled: StateFlow<Boolean> = _inputEnabled.asStateFlow()

    init {
        // Observe agent state changes
        viewModelScope.launch {
            agentEngine.state.collect { state ->
                when (state) {
                    is AgentState.Idle -> {
                        _isRunning.value = false
                        _inputEnabled.value = true
                    }
                    is AgentState.Running -> {
                        _isRunning.value = true
                        _inputEnabled.value = false
                    }
                    is AgentState.Finished -> {
                        _isRunning.value = false
                        _inputEnabled.value = true
                    }
                    is AgentState.Error -> {
                        _isRunning.value = false
                        _inputEnabled.value = true
                        _messages.value = _messages.value + ChatMessageUi.Agent("⚠️ ${state.message}")
                    }
                }
            }
        }
    }

    /**
     * Update the system banner text (for localization).
     */
    fun setBanner(text: String) {
        val current = _messages.value
        if (current.isNotEmpty() && current.first() is ChatMessageUi.System) {
            _messages.value = listOf(ChatMessageUi.System(text)) + current.drop(1)
        }
    }

    /**
     * Submit a task for the agent to execute.
     */
    fun submitTask(task: String) {
        if (task.isBlank() || _isRunning.value) return

        _messages.value = _messages.value + ChatMessageUi.User(task)
        _messages.value = _messages.value + ChatMessageUi.Agent("🤔 思考中...")

        viewModelScope.launch {
            val result = agentEngine.run(task)
            val current = _messages.value.toMutableList()
            val thinkingIndex = current.indexOfLast { it is ChatMessageUi.Agent && it.content == "🤔 思考中..." }
            if (thinkingIndex >= 0) {
                current[thinkingIndex] = ChatMessageUi.Agent(result)
            } else {
                current.add(ChatMessageUi.Agent(result))
            }
            _messages.value = current
        }
    }

    /**
     * Stop the current agent execution.
     */
    fun stopAgent() {
        agentEngine.stop()
    }
}

/**
 * Simulated LLM provider for development/testing.
 */
class SimulatedLlmProvider : com.mengpaw.core.llm.LlmProvider {
    private var callCount = 0

    override suspend fun complete(prompt: String): String {
        callCount++
        return when (callCount) {
            1 -> """
                Thought: I need to check the system status first.
                Action: self.status
                Action Input: {}
            """.trimIndent()
            2 -> """
                Thought: Let me check the available storage.
                Action: self.stats
                Action Input: {}
            """.trimIndent()
            3 -> """
                Thought: The system looks healthy. Let me provide a summary.
                Final Answer: System check complete. All systems operational.
            """.trimIndent()
            else -> """
                Final Answer: Task completed in $callCount steps.
            """.trimIndent()
        }
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val result = complete(prompt)
        result.forEach { onToken(it.toString()) }
        return result
    }

    override fun info(): ProviderInfo = ProviderInfo(
        "Simulated", "simulation-v1", ProviderType.LOCAL
    )
}

/**
 * UI representation of a chat message.
 */
sealed class ChatMessageUi {
    data class User(val content: String) : ChatMessageUi()
    data class Agent(val content: String) : ChatMessageUi()
    data class System(val content: String) : ChatMessageUi()
}
