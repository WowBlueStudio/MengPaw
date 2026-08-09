// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
 * 安全分级权限面板 (v0.34.3 + v0.35.1 表达重构) — Agent 权限等级, per-agent 持久化。
 * 标准: 普通放行 / 中危拒绝 / 高危弹窗; 信任: 中危也放行 / 高危仍弹窗。
 * 高危永远需要用户确认, 不可被权限等级绕过。
 * 表达 (用户定案): 标准=蓝色盾牌 / 信任=粉色盾牌; 普通行绿色 / 中危行随盾牌色 / 高危行红色;
 * 无开关, 点击整个块切换等级。
 */
@Composable
fun AgentPermissionPanel(activeAgentName: String, strings: com.mengpaw.shell.ui.localization.AppStrings) {
    var level by remember(activeAgentName) {
        mutableStateOf(AgentPermissionStore.levelOf(activeAgentName))
    }
    val trusted = level == AgentPermissionLevel.TRUSTED
    val shieldColor = if (trusted) ArcoColors.Pink6 else ArcoColors.Blue6

    SectionHeader(strings.permissionLevelTitle)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            val target = if (trusted) AgentPermissionLevel.STANDARD else AgentPermissionLevel.TRUSTED
            if (AgentPermissionStore.setLevel(activeAgentName, target)) level = target
        },
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCardHigh
    ) {
        Column(Modifier.padding(ArcoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(24.dp), tint = shieldColor)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(if (trusted) strings.permissionTrusted else strings.permissionStandard,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                        color = shieldColor)
                    Text(if (trusted) strings.permissionTrustedDesc else strings.permissionStandardDesc,
                        fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
            Text(strings.permissionLow,
                fontSize = 12.sp, color = ArcoColors.Green6)
            Text(if (trusted) strings.permissionMidTrusted else strings.permissionMidStandard,
                fontSize = 12.sp, color = shieldColor)
            Text(strings.permissionHigh,
                fontSize = 12.sp, color = ArcoColors.Red6)
        }
    }
}
