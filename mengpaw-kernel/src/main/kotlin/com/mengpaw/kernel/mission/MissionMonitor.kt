// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.mission

/** Worker sub-Agent status for Mission monitoring. */
data class WorkerMonitor(
    val id: String, val task: String, val status: String,
    val progress: Int = 0, val output: String = ""
)

/** Verifier sub-Agent status. */
data class VerifierMonitor(
    val totalWorkers: Int = 0, val verified: Int = 0,
    val failed: Int = 0, val currentCheck: String = ""
) {
    val summary: String get() = when {
        totalWorkers == 0 -> "等待 Worker..."
        verified + failed >= totalWorkers ->
            if (failed == 0) "✅ 全部通过 ($verified/$totalWorkers)"
            else "⚠️ $verified/$totalWorkers 通过 ($failed 失败)"
        else -> "验证中... $verified/$totalWorkers"
    }
}

typealias MissionListener = (MissionSnapshot) -> Unit

data class MissionSnapshot(
    val active: Boolean,
    val goal: String,
    val workers: List<WorkerMonitor>,
    val verifier: VerifierMonitor
)

/**
 * Shared mission monitoring state — used by plugins and UI.
 *
 * Plugins call [start], [updateWorker], [stop] during Mission execution.
 * UI observes changes via [addListener] / [removeListener].
 * The shell layer wraps this with Compose-observable state for reactive UI.
 */
object MissionMonitor {
    // 并发安全: Mission 各 worker 协程并发 updateWorker, UI 线程直读快照 —
    // workers/listeners 用 CopyOnWriteArrayList 无锁读 (UI 侧 toList 不 CME),
    // 复合变更 (indexOfFirst+set / verifier 计数) 经 synchronized(this) 串行化
    val workers = java.util.concurrent.CopyOnWriteArrayList<WorkerMonitor>()
    @Volatile
    var verifier = VerifierMonitor()
        private set
    @Volatile
    var missionActive = false
        private set
    @Volatile
    var missionGoal = ""
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<MissionListener>()

    fun addListener(l: MissionListener) { listeners.add(l) }
    fun removeListener(l: MissionListener) { listeners.remove(l) }

    private fun emit() {
        // 锁内构建一致快照, 锁外回调监听器 — 慢监听器不阻塞其他 worker 的更新
        val snapshot = synchronized(this) {
            MissionSnapshot(missionActive, missionGoal, workers.toList(), verifier)
        }
        listeners.toList().forEach { it(snapshot) }
    }

    fun reset() {
        synchronized(this) {
            workers.clear(); verifier = VerifierMonitor()
            missionActive = false; missionGoal = ""
        }
        emit()
    }

    fun start(goal: String, workerCount: Int) {
        synchronized(this) {
            workers.clear(); verifier = VerifierMonitor()
            missionActive = true; missionGoal = goal
            verifier = verifier.copy(totalWorkers = workerCount)
        }
        emit()
    }

    fun updateWorker(id: String, task: String, status: String, progress: Int = 0, output: String = "") {
        synchronized(this) {
            val existing = workers.indexOfFirst { it.id == id }
            val oldStatus = if (existing >= 0) workers[existing].status else ""
            val w = WorkerMonitor(id, task, status, progress, output)
            if (existing >= 0) workers[existing] = w else workers.add(w)
            // Only count terminal transitions once (avoid double-counting on re-updates)
            val v = verifier
            val newVerified = v.verified + (if (status == "verified" && oldStatus != "verified") 1 else 0)
            val newFailed = v.failed + (if (status == "failed" && oldStatus != "failed") 1 else 0)
            verifier = v.copy(verified = newVerified, failed = newFailed)
        }
        emit()
    }

    fun stop() {
        synchronized(this) { missionActive = false }
        emit()
    }
}
