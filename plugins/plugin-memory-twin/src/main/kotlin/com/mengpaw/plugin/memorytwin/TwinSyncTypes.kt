// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.ports.Ports

// ── Supporting types (from TwinSyncEngine, 职责拆分批次3) ──────────────

/** Information about a twin peer known on the LAN. */
data class TwinPeerInfo(
    val peerId: String,
    var agentName: String,
    var address: String,
    var port: Int = Ports.ACP,
    var lastSeen: Long = System.currentTimeMillis(),
    var lastSyncAt: Long = 0L,
    var capabilityCard: String? = null,
    var online: Boolean = true
)

/** Sync phase for UI status display. */
enum class SyncPhase { IDLE, DISCOVERING, SYNCING, MERGING, ERROR }

/** Observable sync state. */
data class TwinSyncState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val totalPeers: Int = 0,
    val onlinePeers: Int = 0,
    val completedPeers: Int = 0,
    val lastFilesReceived: Int = 0,
    val lastConflicts: Int = 0,
    val lastSyncAt: Long = 0L
)

/** Concrete sync result — no more silent "return 0". */
data class TwinSyncResult(
    val filesReceived: Int,
    val filesSent: Int,
    val conflicts: Int,
    val error: String?,
    val suggestion: String?
)
