// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 外部任务入口 (自 AgentViewModel 拆出 — delegate-object 模式):
 * 触发器任务 / 浏览器网页提炼 / 部落收件箱轮询。
 * 依赖经构造器注入: 会话表、会话工厂、任务提交桥接、活动 Agent 名桥接。
 */
internal class AgentTaskInbox(
    private val scope: CoroutineScope,
    private val sessions: MutableMap<String, AgentSession>,
    private val sessionFactory: AgentSessionFactory,
    private val getActiveAgentName: () -> String,
    private val onSubmitTask: (task: String, maxSteps: Int) -> Unit,
) {

    // ── Trigger task: silent background execution ────────────────────

    /**
     * Called by TriggerEngine.onFire when a CRON/SCHEDULE trigger fires.
     */
    fun submitTriggerTask(trigger: com.mengpaw.kernel.trigger.TriggerEngine.Trigger) {
        val targetAgent = DEFAULT_AGENT_NAME
        val session = sessions.getOrPut(targetAgent) { sessionFactory.createSession(targetAgent, null) }

        // Don't interrupt a running agent; queue to inbox for later pickup
        if (session.isRunning.value) {
            val inbox = File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            File(inbox, "trigger_${trigger.id}_${System.currentTimeMillis()}.md").writeText(
                "# 触发器任务\n- ID: ${trigger.id}\n- 类型: ${trigger.type}\n- Cron: ${trigger.config}\n- 时间: ${
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                }\n\n${trigger.action}\n"
            )
            return
        }

        // Minimal prompt — behavior governed by workspace rule files (CRON → heartbeat.md, Truman Show → trumanshow.md).
        val ruleFile = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) "heartbeat.md" else "trumanshow.md"
        val prompt = "[触发器任务 · ${trigger.type}] ${trigger.action}\n(行为规范: 阅读 $ruleFile 获取执行细则)"

        // Light system note so user knows something happened
        session.messages.value = session.messages.value + ChatMessageUi.System(
            "${trigger.action.take(40)}..."
        )

        scope.launch {
            kotlinx.coroutines.delay(200)
            onSubmitTask(prompt, 20)
        }
    }

    // ── Browser extract task: 网页转 Markdown 提炼 ─────────────────────

    /**
     * 浏览器「提炼网页要点」请求 — 后台静默执行。
     * 任务脚本已在 inbox (browser_extract_<taskId>.md), 提示词引用该文件;
     * 会话忙碌时 submitTask 自动入 pending 队列。
     */
    fun submitBrowserExtract(url: String, taskId: String) {
        val targetAgent = DEFAULT_AGENT_NAME
        val session = sessions.getOrPut(targetAgent) { sessionFactory.createSession(targetAgent, null) }

        // Light system note so user knows something happened
        session.messages.value = session.messages.value + ChatMessageUi.System(
            "正在提炼网页要点: ${url.take(40)}..."
        )

        scope.launch {
            kotlinx.coroutines.delay(200)
            onSubmitTask(
                "[浏览器网页提炼任务 · $taskId]\n任务脚本: agent.read ${com.mengpaw.kernel.DataPaths.AGENT_INBOX}/browser_extract_$taskId.md\n按脚本步骤执行, 完成后删除该任务文件。",
                20
            )
        }
    }

    /**
     * 部落收件箱 + 命令集指纹轮询：每 5s 检查待处理部落任务数和
     * Agent Tools 命令集目录指纹，变化时刷新 system prompt（让 Agent 感知新任务/新命令集）。
     * 由 MengPawApp 启动时调用一次。
     */
    fun startTribeInboxRefresh() {
        scope.launch {
            var last = -1
            var lastToolsFp = -1L
            while (true) {
                kotlinx.coroutines.delay(5000)
                val n = try {
                    com.mengpaw.plugin.hermes.TribeInboxWatcher.pendingCount(getActiveAgentName())
                } catch (_: Exception) { 0 }
                val fp = try {
                    com.mengpaw.plugin.agenttools.AgentToolsSummary.fingerprint(getActiveAgentName())
                } catch (_: Exception) { 0L }
                if (n != last || fp != lastToolsFp) {
                    last = n
                    lastToolsFp = fp
                    try { sessions[getActiveAgentName()]?.engine?.refreshSystemPrompt() } catch (_: Exception) {}
                }
            }
        }
    }
}
