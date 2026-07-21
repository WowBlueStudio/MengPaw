// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.mission

/** Worker sub-Agent status for Mission monitoring. */
data class WorkerMonitor(
    val id: String, val task: String, val status: String,
    val progress: Int = 0, val output: String = ""
)

/** Verifier sub-Agent status. */
data class VerifierMonitor(
    var totalWorkers: Int = 0, var verified: Int = 0,
    var failed: Int = 0, var currentCheck: String = ""
) {
    val summary: String get() = when {
        totalWorkers == 0 -> "等待 Worker..."
        verified + failed >= totalWorkers ->
            if (failed == 0) "✅ 全部通过 ($verified/$totalWorkers)"
            else "⚠️ $verified/$totalWorkers 通过 ($failed 失败)"
        else -> "验证中... $verified/$totalWorkers"
    }
}

/** Shared mission monitoring state — used by plugins and UI. */
object MissionMonitor {
    val workers = mutableListOf<WorkerMonitor>()
    val verifier = VerifierMonitor()
    var missionActive = false
    var missionGoal = ""

    fun reset() {
        workers.clear(); verifier.totalWorkers = 0
        verifier.verified = 0; verifier.failed = 0
        missionActive = false; missionGoal = ""
    }

    fun start(goal: String, workerCount: Int) {
        reset(); missionActive = true; missionGoal = goal
        verifier.totalWorkers = workerCount
    }

    fun updateWorker(id: String, task: String, status: String, progress: Int = 0, output: String = "") {
        val existing = workers.indexOfFirst { it.id == id }
        val w = WorkerMonitor(id, task, status, progress, output)
        if (existing >= 0) workers[existing] = w else workers.add(w)
        if (status == "verified") verifier.verified++
        if (status == "failed") verifier.failed++
    }

    fun stop() { missionActive = false }
}
