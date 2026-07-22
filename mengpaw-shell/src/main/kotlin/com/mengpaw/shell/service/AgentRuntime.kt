// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.service

import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.llm.AdaptiveLlmProvider
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.kernel.trigger.TriggerEngine
import com.mengpaw.shell.ui.screens.AgentViewModel
import kotlinx.coroutines.*

/**
 * Background runtime for Agent lifecycle — completely isolated from UI thread.
 *
 * All file I/O and network calls run on IO. ViewModel mutations are posted
 * asynchronously to Main via [viewModelScope] — never blocking the IO thread.
 */
object AgentRuntime {

    /** True while agent is initializing. UI observes this to show loading state. */
    val isInitializing = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var agentViewModel: AgentViewModel? = null

    /**
     * Called when user exits Settings with a valid API key.
     * IO work runs immediately; Main work is posted async via ViewModel scope.
     */
    fun onSettingsSaved(
        endpoint: String,
        apiKey: String,
        model: String,
        agentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE
    ) {
        if (apiKey.isBlank()) return
        val vm = agentViewModel ?: return

        isInitializing.value = true
        scope.launch {
            try {
                val agentName = "MengPaw"

                // ── IO work: file I/O + provider creation ──
                AgentDocs.bootstrap(agentName)

                val provider = AdaptiveLlmProvider(endpoint, apiKey, model)

                val boostFile = java.io.File(
                    com.mengpaw.kernel.DataPaths.AGENTS, "$agentName/boost.md")
                val boostContent = if (boostFile.exists()) {
                    try { boostFile.readText() } catch (_: Exception) { "" }
                } else ""

                val prompt = if (boostContent.isNotBlank()) {
                    "启动。请读取并执行你的工作区引导文件 Boost.md，内容如下：\n\n$boostContent"
                } else {
                    "启动。请介绍你自己，向用户确认以下信息：1) 你的名字 2) 你的定位/角色 3) 你的风格偏好。简洁提问，不要展开。"
                }

                // ── Post UI work to Main looper asynchronously (non-blocking) ──
                withContext(Dispatchers.Main) {
                    vm.applyConfiguration(endpoint, apiKey, model, provider, agentLang)
                    vm.setInitializingMessage(prompt.take(80) + "...")
                }
                // ── Submit task (launches LLM coroutine, returns immediately) ──
                vm.submitTask(prompt, maxSteps = 25)

                // Initialization complete — loading indicator will hide when agent responds
            } catch (e: Exception) {
                com.mengpaw.kernel.KernelLog.w("AgentRuntime", "Init failed: ${e.message}")
            }

            // Hide loading after a short delay to let UI settle
            delay(1000)
            isInitializing.value = false
        }
    }

    /** Wire TriggerEngine → AgentViewModel. Called once at app startup. */
    fun wireTriggers(vm: AgentViewModel) {
        TriggerEngine.onFire = { trigger -> vm.submitTriggerTask(trigger) }
        TriggerEngine.start()
    }
}
