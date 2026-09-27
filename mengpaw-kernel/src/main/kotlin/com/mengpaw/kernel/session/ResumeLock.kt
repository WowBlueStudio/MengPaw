// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 断点续跑单会话锁 — 同一会话同一时刻只允许一个续跑执行体。
 *
 * ## 为什么需要文件锁 (而非仅进程内锁)
 * 续跑入口有三个: ① App 冷启动后的自动续跑; ② 用户点「继续」; ③ 后台保活 (WorkManager/前台服务)
 * 在进程存活期间触发的续跑。三者可能落在**不同进程** (§③ Android 保活常驻独立进程) 或同一进程的
 * 不同协程 — 进程内 `synchronized` 只挡得住后者。检查点本身是"最后写入者赢"的覆盖式落盘, 没有
 * 互斥时两个执行体会互相覆盖进度、重复执行工具副作用。故互斥判据必须是**跨进程可见的磁盘文件**。
 *
 * ## 协议
 * - 锁文件: `{dir}/resume.lock` (dir 默认 [DataPaths.CHECKPOINTS]), JSON 内容 =
 *   [Holder] (sessionId / pid / acquiredAt)。
 * - 获取: 无锁文件 → 写入并持有; 有锁文件且**未过期** → 同会话幂等放行, 异会话拒绝;
 *   有锁文件但**已过期** → 放行抢占 (覆盖), 防崩溃残留的幽灵锁把用户永久挡在门外。
 * - 释放: 仅当锁文件属于本会话时删除 (防误删他人锁)。
 *
 * ## TTL 取值理由 ([DEFAULT_TTL_MS] = 10 分钟)
 * 续跑期间不会续租, TTL 只能靠"长到不会打断正常续跑、短到不会把用户锁在门外"来定:
 * - 太短 (如 1 分钟): 恢复后真正跑任务的一步 (LLM 往返 + 工具执行) 轻易超过 1 分钟, 用户点
 *   「继续」会把正在跑的续跑实例判为过期并抢锁 → 反而是最危险的重复执行。
 * - 太长 (如 1 小时): 进程被系统杀死 (Android 上无 finalizer 保证 [release] 一定执行) 后,
 *   用户重开 App 会被一把幽灵锁挡住 1 小时。
 * 10 分钟 ≈ 单次续跑"最坏一步"的量级 (60s 工具超时 + LLM 往返), 且用户重启 App 后最多等
 * 一个自然段即可重试; 真被挡住时走 [ResumePlanner] 的 `PromptUser` 分支给出明确提示, 不静默失败。
 *
 * ## 可测性
 * [dir] 与 [clock] 均由构造器注入 — 测试用临时目录 + 假时钟, 不做墙钟阈值断言。
 * 纯 `java.io.File` / `java.nio` 实现 (kernel 既有惯例, 见 [SessionEventLog] 的原子写), 无 Android 依赖。
 */
class ResumeLock(
    private val dir: String = DataPaths.CHECKPOINTS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** 进程标识 — 仅用于诊断 (谁持有锁), 不参与判据。 */
    private val pid: Long = ProcessHandle.current().pid(),
) {

    /** 锁持有者信息 (落盘内容)。 */
    data class Holder(val sessionId: String, val pid: Long, val acquiredAt: Long)

    /** 锁被拒的原因 — 供上层拼装用户可读提示。 */
    enum class DeniedReason { HELD_BY_OTHER_SESSION, IO_ERROR }

    /** 上次失败原因 (仅 [DeniedReason.IO_ERROR] 时非 null) — 便于上层上报诊断。 */
    var lastDeniedReason: DeniedReason? = null
        private set

    private val lockFile: File get() = File(dir, LOCK_FILE_NAME)

    /** 本实例当前持锁的会话 id (未持锁为 null)。 */
    var heldSessionId: String? = null
        private set

    /**
     * 尝试获取会话 [sessionId] 的续跑锁。
     *
     * 并发安全: 写入走"临时文件 + [Files.move] 原子替换", 因此 N 个执行体同时抢锁时
     * **恰有一方最后落盘成为持有者**; 但原子替换本身不能保证"只有一个成功" —
     * 真正的互斥判据是紧随其后的**回读校验** ([verifyHeldBy]): 只有回读到的
     * sessionId 与自己一致才算获取成功, 被他人覆盖的一方必然回读失败。
     *
     * @return true = 已持有锁 (调用方负责在续跑结束后 [release]); false = 未获取 (见 [lastDeniedReason])。
     */
    fun tryAcquire(sessionId: String): Boolean {
        if (sessionId.isBlank()) {
            lastDeniedReason = DeniedReason.IO_ERROR
            return false
        }
        // 进程内监视器 — 与下方"回读校验"配合给出**恰好一个赢家**:
        // 若只靠回读, 两线程可能先后写入并各自回读到自己的 sessionId (最后写入者赢),
        // 双方都误判持有; 监视器把"写入 + 回读"变成进程内临界区, 回读结果才可信。
        synchronized(IN_PROCESS_LOCK) {
            lastDeniedReason = null
            return try {
                val now = clock()
                val existing = readHolder()
                if (existing != null && isStale(existing, now)) {
                    // 已过期 = 上一位持有者已死 (崩溃/TTL 超时) → 放行抢占, 否则用户会被幽灵锁永久挡住。
                    // 过期判据同样覆盖"同会话": 同会话的旧执行体若仍活着会持续写检查点,
                    // 但它的 RUNNING 检查点会被本执行体接续, 不产生两条相互矛盾的终态 (最后写入者赢)。
                    KernelLog.i(TAG, "resume.lock 已过期 (holder=${existing.sessionId}), 放行抢占: $sessionId")
                } else if (existing != null) {
                    if (existing.sessionId == sessionId) {
                        // 未过期的同会话重入 (进程内重复触发同一续跑入口) — 幂等放行
                        heldSessionId = sessionId
                        return true
                    }
                    lastDeniedReason = DeniedReason.HELD_BY_OTHER_SESSION
                    return false
                }
                writeHolder(Holder(sessionId, pid, now))
                // 回读校验 — 只有锁文件确实属于自己才算持有 (跨进程抢锁的最终裁决点)
                val confirmed = readHolder()
                if (confirmed?.sessionId != sessionId) {
                    lastDeniedReason = DeniedReason.HELD_BY_OTHER_SESSION
                    return false
                }
                heldSessionId = sessionId
                true
            } catch (e: Exception) {
                // 文件 IO 必须 try/catch: 锁设施故障不得把恢复流程整体打断
                lastDeniedReason = DeniedReason.IO_ERROR
                KernelLog.w(TAG, "tryAcquire 失败 (会话 $sessionId): ${e.message}")
                false
            }
        }
    }

    /** 释放锁 — 仅当锁文件属于 [heldSessionId] 时删除, 防误删他人锁。 */
    fun release(): Boolean = synchronized(IN_PROCESS_LOCK) {
        val mine = heldSessionId
        try {
            if (mine != null && readHolder()?.sessionId == mine) {
                val deleted = lockFile.delete()
                heldSessionId = null
                return@synchronized deleted
            }
        } catch (e: Exception) {
            KernelLog.w(TAG, "release 失败: ${e.message}")
        }
        heldSessionId = null
        false
    }

    /** 当前锁状态 (只读诊断 / 测试用)。 */
    fun peek(): Holder? = try {
        readHolder()
    } catch (_: Exception) {
        null
    }

    /** 是否已过期 (无锁文件视为过期)。 */
    fun isExpired(): Boolean {
        val holder = peek() ?: return true
        return isStale(holder, clock())
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    /** 过期判定: 持有时间 ≥ [ttlMs]。时钟回拨 (now < acquiredAt) 时视为未过期, 保守不抢占。 */
    private fun isStale(holder: Holder, now: Long): Boolean = now - holder.acquiredAt >= ttlMs

    /** 读取锁文件 — 不存在 / 损坏一律返回 null (= 无锁)。 */
    private fun readHolder(): Holder? {
        if (!lockFile.isFile) return null
        val text = lockFile.readText().trim()
        if (text.isEmpty()) return null
        val parts = text.split('|', limit = 3)
        if (parts.size < 3) return null
        val at = parts[2].toLongOrNull() ?: return null
        val sid = parts[0]
        if (sid.isBlank()) return null
        return Holder(sid, parts[1].toLongOrNull() ?: -1L, at)
    }

    /** 原子写锁文件 (tmp + 原子替换), 失败向上抛由 [tryAcquire] 统一处理。 */
    private fun writeHolder(holder: Holder) {
        lockFile.parentFile?.mkdirs()
        val tmp = File(lockFile.parentFile, "$LOCK_FILE_NAME.tmp")
        tmp.writeText("${holder.sessionId}|${holder.pid}|${holder.acquiredAt}")
        Files.move(tmp.toPath(), lockFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    internal companion object {
        const val TAG = "ResumeLock"
        const val LOCK_FILE_NAME = "resume.lock"

        /** 进程内互斥监视器 (见 [tryAcquire] KDoc)。 */
        val IN_PROCESS_LOCK = Any()

        /** 默认 TTL (10 分钟) — 取值理由见类 KDoc; 公开供调用方/测试引用。 */
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    }
}
