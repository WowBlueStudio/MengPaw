// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.security.AgentPermissionLevel
import com.mengpaw.kernel.security.AgentPermissionStore

/**
 * 安全分级权限面板 (v0.34.3) — Agent 权限等级, per-agent 持久化。
 * 标准: 普通放行 / 中危拒绝 / 高危弹窗; 信任: 中危也放行 / 高危仍弹窗。
 * 高危永远需要用户确认, 不可被权限等级绕过。
 */
@Composable
fun AgentPermissionPanel(activeAgentName: String) {
    var level by remember(activeAgentName) {
        mutableStateOf(AgentPermissionStore.levelOf(activeAgentName))
    }

    SectionHeader("安全分级权限")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCardHigh
    ) {
        Column(Modifier.padding(ArcoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = ArcoColors.Blue6)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("权限等级", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text(if (level == AgentPermissionLevel.TRUSTED) "信任 — 中危操作也放行" else "标准 — 中危操作需提升权限",
                        fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
                Switch(
                    checked = level == AgentPermissionLevel.TRUSTED,
                    onCheckedChange = { trusted ->
                        val target = if (trusted) AgentPermissionLevel.TRUSTED else AgentPermissionLevel.STANDARD
                        if (AgentPermissionStore.setLevel(activeAgentName, target)) level = target
                    }
                )
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
            Text("• 普通（新建/写入文件、通知等）— 始终放行", fontSize = 12.sp, color = ThemeColors.textSecondary)
            Text("• 中危（删除/修改文件、剪贴板、截图录屏）— 标准拒绝，信任放行", fontSize = 12.sp, color = ThemeColors.textSecondary)
            Text("• 高危（清空/卸载/系统级/拍照）— 每次弹窗询问，拒绝即阻挡", fontSize = 12.sp, color = ArcoColors.Orange6)
        }
    }
}
