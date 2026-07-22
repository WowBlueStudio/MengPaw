// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * Agent workspace document manager — bootstraps pre-built .md templates
 * and provides read access to agent documents.
 *
 * ## Template flow
 *
 * Templates live as real .md files in APK assets (mengpaw-shell/assets/agent-templates/).
 * At app startup, [AgentTemplates.init] extracts them to a read-only path.
 * When an agent is created, files are copied from there to the agent's workspace.
 *
 * This object delegates the actual file-copy work to a [bootstrapper] callback
 * set by the Android layer — keeping the kernel module zero-Android-dependency.
 *
 * ## Customization
 *
 * Edit the .md files under mengpaw-shell/src/main/assets/agent-templates/zh/,
 * rebuild the APK. No Kotlin source changes needed.
 */
object AgentDocs {

    /**
     * Bootstrap callback set by the Android layer (AgentTemplates.bootstrapAgent).
     * When null (e.g. JVM tests), agent creation is a no-op beyond directory setup.
     */
    @Volatile
    var bootstrapper: ((agentName: String) -> Unit)? = null

    /** Create default doc files for a new agent by copying from pre-built templates. */
    fun bootstrap(agentName: String) {
        val dir = File(DataPaths.AGENTS, agentName)
        if (!dir.exists()) dir.mkdirs()
        // Fast path: if soul.md already exists, bootstrap is done
        if (File(dir, "soul.md").exists()) return
        // Delegate to Android-layer file copy (null-safe: JVM tests skip this)
        bootstrapper?.invoke(agentName)
    }

    /** Read the content of agents.md for a given agent. */
    fun readAgentsDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/agents.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readAgentsDoc"); ""
        } else ""
    }

    /** Read soul.md content. */
    fun readSoulDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/soul.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readSoulDoc"); ""
        } else ""
    }

    /** Read memory.md content. */
    fun readMemoryDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/memory.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readMemoryDoc"); ""
        } else ""
    }
}
