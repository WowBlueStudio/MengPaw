// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * 工作区文档读取器 — 拆自 AgentDocs (400 行文件拆分)。
 * PromptEngine 的系统提示词构建 (PromptSystemBuilder) 即消费这些读取器。
 */
internal class AgentDocsReaders {

    internal fun readAgentsDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/agents.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readAgentsDoc"); ""
        } else ""
    }

    internal fun readProfileDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/profile.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readProfileDoc"); ""
        } else ""
    }

    internal fun readSoulDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/soul.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readSoulDoc"); ""
        } else ""
    }

    /** Read boost.md — first-run bootstrap guidance file. Empty string means no boost needed. */
    internal fun readBoostDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/boost.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readBoostDoc"); ""
        } else ""
    }

    /** Read heartbeat.md — CRON task rules. Empty string means skip all scheduled tasks. */
    internal fun readHeartbeatDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/heartbeat.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readHeartbeatDoc"); ""
        } else ""
    }

    /** Read trumanshow.md — Truman Show (random chat) rules. Empty string = built-in topic pool only. */
    internal fun readTrumanShowDoc(agentName: String): String {
        val file = File(DataPaths.AGENTS, "$agentName/trumanshow.md")
        return if (file.exists()) try { file.readText() } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocs.readTrumanShowDoc"); ""
        } else ""
    }
}
