// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.llm.ThinkingEffort
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * 思考强度四档选择 (v0.46.2) — 官方依据 api-docs.deepseek.com/zh-cn/guides/thinking_mode:
 * 思考模式默认打开且 effort 默认 high, 强度取 low/high/max; Off 走官方开关 disabled。
 * 仅 DeepSeek 端点注入 (内核 effectiveThinkingEffort 过滤), 故调用方按供应商可见性渲染本区块。
 */
@Composable
internal fun ThinkingEffortSection(
    strings: AppStrings,
    current: ThinkingEffort,
    onSelect: (ThinkingEffort) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(strings.agentThinkingEffort, style = MaterialTheme.typography.labelSmall,
            color = ThemeColors.textSecondary)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 档位顺序按用户定案: Max / High / Low / Off
            ThinkingEffort.entries.forEach { effort ->
                val selected = effort == current
                Surface(
                    modifier = Modifier.clickable { onSelect(effort) },
                    shape = RoundedCornerShape(ArcoRadius.sm),
                    color = if (selected) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh
                ) {
                    Text(
                        effort.label,
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) ThemeColors.brand else ThemeColors.textPrimary
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(strings.agentThinkingEffortDesc, fontSize = 10.sp, color = ThemeColors.textSecondary,
            modifier = Modifier.padding(top = ArcoSpacing.xs))
    }
}
