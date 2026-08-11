// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import java.util.concurrent.ConcurrentHashMap

/**
 * 主动行为检测器 (v0.34.3 P0-2 ① 铲子检测方案) — 会话行为基线。
 *
 * 威胁模型: 第三方 LLM 供应商/中转代理在服务端植入恶意行为 ("铲子") —
 * 模型在无用户意图驱动时主动连续执行写/外联命令 (探测 → 写文件 → 外联回传)。
 * 检测特征: **无读取操作间隔的连续写/外联命令序列** (正常 Agent 是
 * 读→判断→写; 铲子倾向直接连写连外联)。
 *
 * 只告警不阻断 — 硬拦截由 P0-3 分级 (高危弹窗/中危权限) 承担; 本检测是
 * 给用户/审计的第二道可见信号。每会话每触发模式只告警一次 (防刷屏)。
 */
object ProactiveBehaviorDetector {

    /** 连续写/外联命令阈值 — 达到即视为异常序列 (可调)。 */
    const val WRITE_STREAK_THRESHOLD = 4

    /** 写类/外联类命令前缀 (含数据外泄通道 net./tavily./render./comfy.)。 */
    private val WRITE_PREFIXES = listOf(
        // v0.36.x 去重: agent.* 文件命令已移除 — 连续写/外联检测改为 Linux 写命令
        "echo", "tee", "printf", "cp", "mv", "rm", "mkdir", "curl", "wget",
        "agent.memory.keep", "agent.memory.record", "agent.memory.write",
        "agent.memory.project.save", "agent.memory.rm", "agent.memory.edit",
        "plugin.install", "plugin.uninstall", "plugin.enable", "plugin.disable", "plugin.update",
        "clipboard.", "skill.enable", "skill.disable",
        "sys.clipboard.set", "sys.notification.send", "sys.app.uninstall", "sys.overlay.",
        "sys.screenrecord.start", "sys.calendar.delete",
        "proc.", "root.", "net.", "tavily.", "render.", "comfy."
    )

    private class SessionState {
        var writeStreak = 0
        var totalWrites = 0
        var reads = 0
        var alerted = false
    }

    private val sessions = ConcurrentHashMap<String, SessionState>()

    /**
     * 记录一条命令并更新会话基线。
     * @return 需要注入会话的告警文本 (异常序列首次触发时), 无则 null。
     */
    fun recordCommand(sessionId: String?, commandLine: String): String? {
        if (sessionId == null) return null
        val state = sessions.computeIfAbsent(sessionId) { SessionState() }
        val name = commandLine.trim().split(" ").firstOrNull() ?: return null
        val isWrite = WRITE_PREFIXES.any { name.startsWith(it) }
        if (isWrite) {
            state.writeStreak++
            state.totalWrites++
            if (!state.alerted && state.writeStreak >= WRITE_STREAK_THRESHOLD) {
                state.alerted = true
                return "⚠️ [主动行为告警] 检测到连续 ${state.writeStreak} 条写/外联命令且无读取操作间隔。" +
                    "若这些操作不是用户当前任务要求的，请立即停止并如实告知用户。"
            }
        } else {
            state.writeStreak = 0
            state.reads++
        }
        return null
    }

    /** 会话结束清理。 */
    fun endSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** 测试隔离。 */
    fun resetForTest() {
        sessions.clear()
    }
}
