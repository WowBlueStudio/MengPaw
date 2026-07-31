// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.agent.PostCallMiddleware
import com.mengpaw.kernel.agent.PostCallResult
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.llm.AdaptiveLlmProvider
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.shell.ui.screens.model.UnconfiguredLlmProvider
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Factory for creating agent sessions, agents, and providers.
 * Shared global configuration for LLM endpoints is stored here.
 */
class AgentSessionFactory(
    private val sessions: MutableMap<String, AgentSession>,
    private val viewModelScope: CoroutineScope,
    private val bootstrappedAgents: MutableSet<String>,
    private val onSubmitTask: (task: String, maxSteps: Int) -> Unit,
    private val onSwitchAgent: (String) -> Unit,
) {

    // ── Global LLM config (shared across new agents as default) ──
    var globalEndpoint: String = ""
    var globalApiKey: String = ""
    var globalModel: String = "unknown"
    var globalAgentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE

    fun defaultProvider(): LlmProvider =
        if (globalApiKey.isBlank()) UnconfiguredLlmProvider()
        else try { AdaptiveLlmProvider(globalEndpoint, globalApiKey, globalModel) } catch (_: Exception) { UnconfiguredLlmProvider() }

    fun createProviderForSession(endpoint: String, apiKey: String, model: String): LlmProvider =
        if (apiKey.isBlank()) UnconfiguredLlmProvider()
        else try { AdaptiveLlmProvider(endpoint, apiKey, model) }
        catch (e: Exception) {
            KernelLog.w("AgentViewModel", "Cannot create real provider, using unconfigured: ${e.message}")
            UnconfiguredLlmProvider()
        }

    fun createSession(name: String, framework: String?): AgentSession {
        val model = globalModel.ifBlank { "unknown" }
        val provider = defaultProvider()

        // Scroll context manager — eviction index + recall per agent
        val scroll = ScrollContextManager(name)

        // Memory middleware: inject essential agent docs only (soul + agents)
        val memoryMw = AgentMiddleware { prompt, agentName ->
            val soul = com.mengpaw.kernel.agent.AgentDocs.readSoulDoc(agentName)
            if (soul.isNotBlank() && soul !in prompt) {
                "$prompt\n\n## 智能体身份\n\n$soul"
            } else prompt
        }
        // Tribe inbox middleware: inject pending tribe task count into prompt
        val tribeMw = com.mengpaw.plugin.hermes.TribeInboxMiddleware
        // Agent Tools middleware: inject registered command-set summaries (tools.import)
        val agentToolsMw = com.mengpaw.plugin.agenttools.AgentToolsSummaryMiddleware

        // Post-call middleware: context folding + scroll eviction
        val postMw = PostCallMiddleware { response, step, totalChars, estimatedTokens ->
            val ratio = estimatedTokens.toDouble() / 131_072.0
            if (ratio > 0.80) {
                PostCallResult(response, shouldFold = true,
                    foldReason = "Step $step: context at ${(ratio * 100).toInt()}%")
            } else {
                PostCallResult(response)
            }
        }

        val engine = AgentEngine(
            llmProvider = provider,
            middleware = AgentMiddleware.chain(memoryMw, tribeMw, agentToolsMw),
            postCallMiddleware = postMw,
            scrollContext = scroll,
            additionalNamespaces = mapOf("sys" to com.mengpaw.core.namespace.SysExecutor.commands)
        ).also {
            it.integrityProvider = com.mengpaw.core.security.IntegrityGuard.globalInstance
            it.setAgentIdentity(name, framework, model)
            it.setAgentLanguage(globalAgentLang)
            it.configureCacheStrategy(globalEndpoint)
        }

        // Inject provider into TribePlugin for LLM routing (tribe.route / fleet)
        try {
            com.mengpaw.plugin.hermes.TribePlugin.llmProvider = provider
        } catch (_: Exception) {}

        val msgs = MutableStateFlow<List<ChatMessageUi>>(
            if (globalApiKey.isBlank())
                listOf(ChatMessageUi.System("欢迎使用 MengPaw。请先进入设置 → 框架设置，配置 API Key 和模型。"))
            else
                listOf(ChatMessageUi.System("$name 就绪。请描述你想完成的任务。"))
        )
        return AgentSession(name, framework, model, globalEndpoint, globalApiKey, provider, engine, msgs, scroll)
    }

    /** Ensure the default "MengPaw" agent session always exists, with workspace files. */
    fun ensureDefaultSession() {
        if (!sessions.containsKey("MengPaw")) {
            sessions["MengPaw"] = createSession("MengPaw", null)
        }
        // Bootstrap workspace files if missing (safe: writeIfMissing won't overwrite existing).
        com.mengpaw.kernel.agent.AgentDocs.bootstrap("MengPaw", if (globalAgentLang == PromptEngine.AgentLanguage.CHINESE) "zh" else "en")
    }

    /** Create a new agent with the given name and optional framework. */
    fun createAgent(name: String, framework: String? = null) {
        createAgentWithDetails(name, name, "", framework)
    }

    /**
     * Create a new agent with full details.
     * @param name Agent display name
     * @param workspaceFolder Folder name for workspace (under AGENTS/)
     * @param intro Agent introduction/bio
     * @param framework Optional remote framework
     */
    fun createAgentWithDetails(
        name: String,
        workspaceFolder: String,
        intro: String,
        framework: String? = null
    ) {
        if (sessions.containsKey(name)) return

        // Bootstrap agent documentation files into the workspace folder
        com.mengpaw.kernel.agent.AgentDocs.bootstrap(workspaceFolder)

        // Save profile with intro
        if (intro.isNotBlank()) {
            val profile = com.mengpaw.kernel.agent.AgentProfile(
                agentName = name,
                name = name,
                bio = intro
            )
            com.mengpaw.kernel.agent.AgentProfile.save(workspaceFolder, profile)
        }

        // Create session and switch to new agent
        sessions[name] = createSession(name, framework)
        onSwitchAgent(name)

        // Auto-start: send "启动" — agent reads Boost.md and begins onboarding
        autoStartAgent(name, workspaceFolder)
    }

    /**
     * Auto-start a newly created agent: sends "启动" message so the agent reads
     * Boost.md from its workspace and proactively engages with the user.
     */
    fun autoStartAgent(agentName: String, workspaceFolder: String) {
        val session = sessions[agentName] ?: return
        bootstrappedAgents.add(agentName)
        // Read Boost.md content for the agent to process on startup
        val boostFile = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$workspaceFolder/boost.md")
        val boostContent = if (boostFile.exists()) {
            try { boostFile.readText(java.nio.charset.Charset.forName("UTF-8")) } catch (_: Exception) { "" }
        } else ""

        // Set initial system message, then trigger agent startup
        session.messages.value = listOf(
            ChatMessageUi.System("$agentName 已创建。正在读取工作区引导文件...")
        )

        // Submit startup task — agent reads Boost.md and proactively engages
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val prompt = if (boostContent.isNotBlank()) {
                "启动。请读取并执行你的工作区引导文件 Boost.md，内容如下：\n\n$boostContent"
            } else {
                "启动。请介绍你自己并询问用户如何配置你的身份。"
            }
            onSubmitTask(prompt, 30)
        }
    }
}
