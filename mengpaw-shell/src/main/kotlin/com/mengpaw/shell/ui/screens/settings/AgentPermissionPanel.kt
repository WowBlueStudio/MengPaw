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
fun AgentPermissionPanel(activeAgentName: String) {
    var level by remember(activeAgentName) {
        mutableStateOf(AgentPermissionStore.levelOf(activeAgentName))
    }
    val trusted = level == AgentPermissionLevel.TRUSTED
    val shieldColor = if (trusted) ArcoColors.Pink6 else ArcoColors.Blue6

    SectionHeader("安全分级权限")
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
                    Text(if (trusted) "信任" else "标准",
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                        color = shieldColor)
                    Text(if (trusted) "信任 — 中危操作也放行（点击切换为标准）" else "标准 — 中危操作需提升权限（点击切换为信任）",
                        fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
            Text("• 普通（新建/写入文件、通知等）— 始终放行",
                fontSize = 12.sp, color = ArcoColors.Green6)
            Text("• 中危（删除/修改文件、剪贴板、截图录屏）— ${if (trusted) "信任放行" else "标准拒绝"}",
                fontSize = 12.sp, color = shieldColor)
            Text("• 高危（清空/卸载/系统级/拍照）— 每次弹窗询问，拒绝即阻挡",
                fontSize = 12.sp, color = ArcoColors.Red6)
        }
    }
}
