// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.mission.MissionMonitor
import com.mengpaw.kernel.mission.MissionSnapshot
import com.mengpaw.kernel.mission.WorkerMonitor

/**
 * Two floating monitor windows for Mission mode:
 * 1. Workers Monitor — left side, shows all Worker sub-Agent statuses
 * 2. Verifier Monitor — right side, shows verification progress
 *
 * Toggled from the main chat screen when Mission is active.
 * Uses [MissionMonitor.addListener] to stay reactive to worker/verifier updates.
 */
@Composable
fun MissionMonitorOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var showWorkers by remember { mutableStateOf(true) }
    var showVerifier by remember { mutableStateOf(true) }
    var minimized by remember { mutableStateOf(false) }

    // Compose-observable snapshot synced from kernel MissionMonitor via listener
    var snapshot by remember { mutableStateOf(MissionSnapshot(false, "", emptyList(), com.mengpaw.kernel.mission.VerifierMonitor())) }

    DisposableEffect(Unit) {
        val listener: com.mengpaw.kernel.mission.MissionListener = { snapshot = it }
        MissionMonitor.addListener(listener)
        // Initial sync
        snapshot = MissionSnapshot(MissionMonitor.missionActive, MissionMonitor.missionGoal, MissionMonitor.workers.toList(), MissionMonitor.verifier)
        onDispose { MissionMonitor.removeListener(listener) }
    }

    if (minimized) {
        // Minimized floating button
        FloatingActionButton(
            onClick = { minimized = false },
            modifier = Modifier.padding(16.dp),
            containerColor = ArcoColors.Blue6,
            shape = CircleShape
        ) {
            Icon(Icons.Outlined.Monitor, "展开监控", tint = Color.White)
        }
        return
    }

    // Full overlay with two panels
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top bar
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🎯 Mission 监控", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Row {
                IconButton(onClick = { showWorkers = !showWorkers }, modifier = Modifier.size(28.dp)) {
                    Icon(if (showWorkers) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, "Workers", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { showVerifier = !showVerifier }, modifier = Modifier.size(28.dp)) {
                    Icon(if (showVerifier) Icons.Outlined.VerifiedUser else Icons.Outlined.VisibilityOff, "Verifier", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { minimized = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Minimize, "最小化", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Mission goal
        if (snapshot.goal.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(ArcoRadius.md), color = Color.White.copy(alpha = 0.19f)) {
                Text(snapshot.goal, Modifier.padding(12.dp), fontSize = 13.sp, color = ArcoColors.Gray4, maxLines = 2)
            }
        }

        // Two panels side by side (or stacked if one hidden)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Workers panel (left)
            if (showWorkers) {
                Surface(
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(ArcoRadius.lg),
                    color = ArcoColors.Gray10.copy(alpha = 0.88f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("👷 Workers (${snapshot.workers.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        if (snapshot.workers.isEmpty()) {
                            Text("等待 Worker 启动...", fontSize = 12.sp, color = ArcoColors.Gray6, modifier = Modifier.padding(8.dp))
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(snapshot.workers) { w -> WorkerCard(w) }
                        }
                    }
                }
            }

            // Verifier panel (right)
            if (showVerifier) {
                Surface(
                    Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(ArcoRadius.lg),
                    color = ArcoColors.Gray10.copy(alpha = 0.88f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("🔍 Verifier", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        val v = snapshot.verifier
                        Text(v.summary, fontSize = 13.sp, color = when {
                            v.failed > 0 -> ArcoColors.Orange6
                            v.verified + v.failed >= v.totalWorkers && v.failed == 0 -> ArcoColors.Green6
                            else -> ArcoColors.Gray6
                        })
                        Spacer(Modifier.height(8.dp))
                        // Progress bar
                        if (v.totalWorkers > 0) {
                            LinearProgressIndicator(
                                progress = { (v.verified + v.failed).toFloat() / v.totalWorkers },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (v.failed == 0) ArcoColors.Green6 else ArcoColors.Orange6,
                                trackColor = Color.White.copy(alpha = 0.19f)
                            )
                        }
                        if (v.currentCheck.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(v.currentCheck, fontSize = 11.sp, color = ArcoColors.Gray6)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerCard(w: WorkerMonitor) {
    val (bg, border) = when (w.status) {
        "verified" -> ArcoColors.Green6.copy(alpha = 0.125f) to ArcoColors.Green6
        "running" -> ArcoColors.Blue6.copy(alpha = 0.125f) to ArcoColors.Blue6
        "failed" -> ArcoColors.Red6.copy(alpha = 0.125f) to ArcoColors.Red6
        "done" -> ArcoColors.Green6.copy(alpha = 0.125f) to ArcoColors.Green6
        else -> Color.White.copy(alpha = 0.125f) to ArcoColors.Gray6
    }
    val icon = when (w.status) {
        "verified" -> "✅"; "running" -> "▶️"; "failed" -> "❌"; "done" -> "👍"; else -> "⬜"
    }

    Surface(
        shape = RoundedCornerShape(ArcoRadius.sm),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Text(w.id, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                if (w.progress > 0 && w.status == "running") {
                    Text("${w.progress}%", fontSize = 10.sp, color = ArcoColors.Gray6)
                }
            }
            Text(w.task.take(40), fontSize = 11.sp, color = ArcoColors.Gray4, maxLines = 1)
            // Progress bar for running workers
            if (w.status == "running") {
                LinearProgressIndicator(
                    progress = { w.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp).clip(RoundedCornerShape(2.dp)),
                    color = ArcoColors.Blue6, trackColor = Color.White.copy(alpha = 0.125f)
                )
            }
        }
    }
}
