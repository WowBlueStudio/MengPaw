// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import java.io.File

/**
 * 恢复决策 (P0-3 / P1-6 接线点) — **纯函数**: 输入事实, 输出 [ResumePlan], 不做任何副作用。
 *
 * ## 为什么要独立于 [decideRecovery]
 * [decideRecovery] 只回答"会话事件看起来像哪种中断"(语义判定, 已实现且有测试锁定);
 * 是否**自动**把任务接着跑下去还取决于三个生产条件: ① 用户开关; ② 单会话锁能否拿到;
 * ③ 检查点是否处于可续跑的 RUNNING 态。本类只做"叠加", 不改动 [decideRecovery] 的判定语义 —
 * 这样两层各自可测, 语义演进互不牵连。
 *
 * ## 判定顺序 (先决条件优先, 后决者不参与)
 * | # | 条件 | 结果 |
 * |---|------|------|
 * | 1 | 无检查点 | [ResumePlan.Skip] (NO_CHECKPOINT) |
 * | 2 | 检查点非 RUNNING (COMPLETED/FAILED) | [ResumePlan.Skip] (ALREADY_TERMINAL) — 按新任务跑 |
 * | 3 | `auto_resume` 开关为关 | [ResumePlan.PromptUser] (AUTO_RESUME_DISABLED) |
 * | 4 | [decideRecovery] 判 [RecoveryDecision.SuggestCleanup] (连续错误 ≥5) | [ResumePlan.PromptUser] (RECOVERY_DECLINED) — 错误态盲续跑只会放大成本 |
 * | 5 | 单会话锁不可得 (他会话正在续跑) | [ResumePlan.PromptUser] (LOCK_UNAVAILABLE) |
 * | 6 | 其余 (含 decideRecovery 判 SimpleRetry / RecoverFromInterrupt / NoAction) | [ResumePlan.AutoResume] |
 *
 * ## auto_resume 开关
 * 读 `{DataPaths.CONFIG}/auto_resume` 文件内容是否等于 `"true"` (与项目既有 `auto_translate`
 * 开关同构)。**文件不存在视为开** (默认启用自动续跑, 这是本功能的存在意义); 读失败也按开处理 —
 * 开关是"用户显式关闭"的旁路, 不该因 IO 抖动把功能整体关掉。
 *
 * ## 锁的语义
 * 只有 [ResumePlan.AutoResume] 会**真正持有**锁 (返回前 acquire, 由调用方在续跑结束后 release);
 * [ResumePlan.PromptUser] / [ResumePlan.Skip] 不影响锁状态。锁的 TTL 与可靠性说明见 [ResumeLock]。
 */
class ResumePlanner(
    private val lock: ResumeLock,
    private val autoResumeFile: File = File(DataPaths.CONFIG, AUTO_RESUME_FLAG),
) {

    /**
     * 给出恢复计划。
     * @param checkpoint 该会话最近的检查点 (null = 无)
     * @param recentEvents 最近的会话事件 (供 [decideRecovery])
     * @param messages 该会话的消息快照 (供 [decideRecovery] 的待恢复扫描)
     */
    fun plan(
        checkpoint: Checkpoint?,
        recentEvents: List<SessionEventBus.SessionEvent>,
        messages: List<Message>
    ): ResumePlan {
        if (checkpoint == null) {
            return ResumePlan.Skip(ResumeSkipReason.NO_CHECKPOINT)
        }
        // 只有 RUNNING 才可续跑 — COMPLETED/FAILED 说明上一轮有明确结论, 新消息按新任务处理
        if (checkpoint.status != CheckpointStatus.RUNNING) {
            return ResumePlan.Skip(ResumeSkipReason.ALREADY_TERMINAL)
        }
        if (!autoResumeEnabled()) {
            return ResumePlan.PromptUser(ResumeSkipReason.AUTO_RESUME_DISABLED)
        }
        // ── P1-6: 既有分级恢复判定 (语义一字不改, 只做叠加) ──
        when (decideRecovery(recentEvents, messages)) {
            is RecoveryDecision.SuggestCleanup ->
                return ResumePlan.PromptUser(ResumeSkipReason.RECOVERY_DECLINED)
            else -> Unit // 其余判定不阻断自动续跑 (SimpleRetry / RecoverFromInterrupt / NoAction)
        }
        if (!lock.tryAcquire(checkpoint.sessionId)) {
            return ResumePlan.PromptUser(ResumeSkipReason.LOCK_UNAVAILABLE)
        }
        return ResumePlan.AutoResume(
            sessionId = checkpoint.sessionId,
            task = resolveTask(checkpoint),
            step = checkpoint.step,
            lastStep = checkpoint.step,
            messages = checkpoint.messages,
            pendingIntents = readPendingIntents(checkpoint.sessionId)
        )
    }

    /**
     * 续跑用任务文本 — **以 messages 为准**: 检查点的 `remainingTask` 写于落盘那一刻, 而
     * messages 里最后一条 user 消息才是模型真正看到的当前任务 (例如中途注入了"继续。"提示词)。
     * 二者不一致时取 messages 末条 user (与 [com.mengpaw.kernel.AgentReActLoop] 的恢复注释一致),
     * messages 内无 user 才回落 `remainingTask`。
     */
    fun resolveTask(checkpoint: Checkpoint): String {
        val fromMessages = checkpoint.messages.lastOrNull { it.role == "user" }?.content
        return fromMessages?.takeIf { it.isNotBlank() } ?: checkpoint.remainingTask
    }

    /** 自动续跑开关 — 文件不存在/读失败一律视为开 (默认启用)。 */
    fun autoResumeEnabled(): Boolean = try {
        if (!autoResumeFile.exists()) true else autoResumeFile.readText().trim() == "true"
    } catch (e: Exception) {
        KernelLog.w(TAG, "auto_resume 开关读取失败, 按默认(开)处理: ${e.message}")
        true
    }

    /** 待确认的工具意图 — 读失败按"无"处理 (锁已持有, 不能因日志不可读而阻断续跑)。 */
    private fun readPendingIntents(sessionId: String): List<String> = try {
        ToolIntentLog().pending(sessionId).map { it.toolName }.filter { it.isNotBlank() }.distinct()
    } catch (e: Exception) {
        KernelLog.w(TAG, "pending intents 读取失败 (会话 $sessionId): ${e.message}")
        emptyList()
    }

    private companion object {
        const val TAG = "ResumePlanner"

        /** 开关文件名 (与 auto_translate 同构: `{CONFIG}/<flag>` 内容为 "true"/"false")。 */
        const val AUTO_RESUME_FLAG = "auto_resume"
    }
}

/** 恢复计划 — 调用方据 [ResumePlan.AutoResume] 触发续跑, 其余分支不得触发。 */
sealed class ResumePlan {

    /**
     * 可自动续跑 (锁已由 [ResumePlanner.plan] 获取; 调用方负责在结束后 release)。
     * @param sessionId 续跑会话 id (沿用检查点原 id — 见 `History.restoreSession`)
     * @param task 续跑任务文本 (以 messages 末条 user 为准)
     * @param step 已消耗步数 (步号从这里接续)
     * @param lastStep 最近一次检查点步号 (诊断/展示)
     * @param messages 检查点里的消息快照 — 引擎据此重建会话历史 (含工具 Observation)
     * @param pendingIntents 未确认完成的工具名列表 — 非空时必须注入"先核对再重试"提示
     */
    data class AutoResume(
        val sessionId: String,
        val task: String,
        val step: Int,
        val lastStep: Int,
        val messages: List<Message> = emptyList(),
        val pendingIntents: List<String> = emptyList()
    ) : ResumePlan()

    /** 需要用户确认/介入 — 不自动续跑, 由 UI 给出可读提示。 */
    data class PromptUser(val reason: ResumeSkipReason) : ResumePlan()

    /** 无需任何动作 (无检查点 / 已有终态结论)。 */
    data class Skip(val reason: ResumeSkipReason) : ResumePlan()
}

/** 非自动续跑的原因 — 既作机器判据, 也直接构成用户可读提示文案。 */
enum class ResumeSkipReason(val userMessage: String) {
    NO_CHECKPOINT("未找到可续跑的进度记录。"),
    ALREADY_TERMINAL("上次任务已有明确结局，无需续跑。"),
    AUTO_RESUME_DISABLED("自动续跑已关闭，可手动回复「继续」接着跑。"),
    RECOVERY_DECLINED("上一轮连续出错，已暂停自动续跑。建议检查配置或结束该任务后重试。"),
    LOCK_UNAVAILABLE("该会话已有续跑在进行中，请稍候或手动回复「继续」。")
}
