// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
 * 默认主 Agent 名 — 触发器任务 / 浏览器提炼 / 横幅切回 / 梦境整理 / 会话兜底等
 * 目标 agent 逻辑的唯一事实源。
 * (P2 修复: 原 "MengPaw" 字面量散落 shell 多处硬编码, 新增/改名需逐处找)
 */
const val DEFAULT_AGENT_NAME = "MengPaw"

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

    /** 角色模型路由 — Fleet/火种各角色 → provider 快照（设置页配置，缺省回退主模型）。 */
    @Volatile
    var globalSwarmRoles: Map<String, SavedProvider> = emptyMap()
        set(value) {
            field = value
            rolesProviderCache = null  // 配置变化 → 缓存失效（P2 修复）
        }

    /** 角色 provider 缓存 — 避免每次 Swarm/Fleet 运行新建 AdaptiveLlmProvider（HttpClient 开销）。 */
    @Volatile
    private var rolesProviderCache: Map<String, LlmProvider>? = null

    /** 组装角色 → LlmProvider（跳过无 key/构造失败的条目；配置不变时复用实例）。 */
    fun buildSwarmRoles(): Map<String, LlmProvider> {
        rolesProviderCache?.let { return it }
        val built = globalSwarmRoles.mapNotNull { (role, sp) ->
            if (sp.endpoint.isBlank() || sp.apiKey.isBlank()) null
            else try { role to AdaptiveLlmProvider(sp.endpoint, sp.apiKey, sp.model,
                networkGate = com.mengpaw.shell.service.NetworkConditionMonitor) }
            catch (e: Exception) {
                KernelLog.w("AgentVM", "角色 $role provider 构造失败，已跳过: ${e.message}")
                null
            }
        }.toMap()
        rolesProviderCache = built
        return built
    }

    fun defaultProvider(): LlmProvider =
        if (globalApiKey.isBlank()) UnconfiguredLlmProvider()
        else try { AdaptiveLlmProvider(globalEndpoint, globalApiKey, globalModel,
            networkGate = com.mengpaw.shell.service.NetworkConditionMonitor) } catch (_: Exception) { UnconfiguredLlmProvider() }

    fun createProviderForSession(endpoint: String, apiKey: String, model: String): LlmProvider =
        if (apiKey.isBlank()) UnconfiguredLlmProvider()
        else try { AdaptiveLlmProvider(endpoint, apiKey, model,
            networkGate = com.mengpaw.shell.service.NetworkConditionMonitor) }
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
        // 言简意赅 middleware: 前缀注入简洁引导（放链尾 — 对前序注入后的完整提示词生效；插件停用即原样返回）
        val conciseMw = com.mengpaw.plugin.concise.ConciseMiddleware

        // Post-call middleware: context folding + scroll eviction
        // P2 修复: 阈值延迟读引擎 compactRatio — applyConfiguration 换模型后
        // setAgentIdentity 更新引擎档位, postMw 跟随（不再锁死创建时的模型档）
        val engineRef = arrayOfNulls<AgentEngine>(1)
        val postMw = PostCallMiddleware { response, step, totalChars, estimatedTokens ->
            val threshold = engineRef[0]?.compactRatio
                ?: com.mengpaw.kernel.PipelineManager.compactRatioFor(model)
            val ratio = estimatedTokens.toDouble() /
                com.mengpaw.kernel.PipelineManager.DEFAULT_CONTEXT_WINDOW
            if (ratio > threshold) {
                PostCallResult(response, shouldFold = true,
                    foldReason = "Step $step: context at ${(ratio * 100).toInt()}%")
            } else {
                PostCallResult(response)
            }
        }

        val engine = AgentEngine(
            llmProvider = provider,
            // FIX(自检报告 P0-2): 注入全局插件管理器 — 此前用默认空实例,
            // CLI.md 插件表恒为空, agent.cli 返回无插件参考。
            pluginManager = com.mengpaw.kernel.plugin.PluginManager.globalInstance,
            middleware = AgentMiddleware.chain(memoryMw, tribeMw, agentToolsMw, conciseMw),
            postCallMiddleware = postMw,
            scrollContext = scroll,
            additionalNamespaces = mapOf("sys" to com.mengpaw.core.namespace.SysExecutor.commands)
        ).also {
            it.integrityProvider = com.mengpaw.core.security.IntegrityGuard.globalInstance
            it.setAgentIdentity(name, framework, model)
            it.setAgentLanguage(globalAgentLang)
            it.configureCacheStrategy(globalEndpoint)
            // FIX(自检报告 P0-2): 预热 CLI.md — 幂等 (插件活跃数比对, 配置反复 apply 不重复写盘)
            engineRef[0] = it  // postMw 延迟读引擎折叠档位（P2 修复）
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

    /** Ensure the default agent session always exists, with workspace files. */
    fun ensureDefaultSession() {
        if (!sessions.containsKey(DEFAULT_AGENT_NAME)) {
            sessions[DEFAULT_AGENT_NAME] = createSession(DEFAULT_AGENT_NAME, null)
        }
        // Bootstrap workspace files if missing (safe: writeIfMissing won't overwrite existing).
        com.mengpaw.kernel.agent.AgentDocs.bootstrap(DEFAULT_AGENT_NAME, if (globalAgentLang == PromptEngine.AgentLanguage.CHINESE) "zh" else "en")
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
