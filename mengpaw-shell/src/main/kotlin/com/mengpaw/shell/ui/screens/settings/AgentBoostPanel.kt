// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * Agent 引导进度 (P2-13): 身份/头像/主题/灵魂 四项打勾。
 *
 * 数据来源均为文件系统, 读取一次不轮询:
 *  - 身份: {AGENTS}/{agent}/profile.md 已填写名字 (兼容模板 `- **名字：**` 与 AgentProfile `- 名称:` 格式)
 *  - 头像: {AGENTS}/{agent}/avatar.png
 *  - 主题: {AGENTS}/theme.md (全局, self.theme 写入位置)
 *  - 灵魂: {AGENTS}/{agent}/soul.md
 * 引导完成标记: boost.md 存在 = 引导流程仍在进行。
 *
 * remember(agentName) 保证进入设置页时读一次 — 设置页关闭再进入时 composition
 * 重建自动刷新; 不实时轮询 (与工作区文件列表的刷新机制无关)。
 */
@Composable
internal fun AgentBoostPanel(agentName: String, strings: AppStrings) {
    val status = remember(agentName) { AgentBoostStatus.check(agentName) }

    SectionHeader(strings.agentBoostPanel)
    Text(strings.agentBoostPanelDesc,
        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            BoostCheckRow(done = status.identity, label = strings.agentBoostItemIdentity)
            BoostCheckRow(done = status.avatar, label = strings.agentBoostItemAvatar)
            BoostCheckRow(done = status.theme, label = strings.agentBoostItemTheme)
            BoostCheckRow(done = status.soul, label = strings.agentBoostItemSoul)

            HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.xs), color = ThemeColors.border)

            if (status.allDone) {
                // 全部完成 → 绿色对勾 + "已完成初始化"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = ArcoColors.Green6)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.agentBoostDone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ArcoColors.Green6)
                }
            } else {
                // 未完成 → n/4 + 缺失项名称
                Text(
                    String.format(strings.agentBoostProgress, status.doneCount,
                        status.missingLabels(
                            strings.agentBoostItemIdentity, strings.agentBoostItemAvatar,
                            strings.agentBoostItemTheme, strings.agentBoostItemSoul
                        ).joinToString(" / ")),
                    fontSize = 13.sp, color = ThemeColors.textSecondary)
            }
            // 四项都齐但 boost.md 仍在 → 提示可在工作区文件里删除 (引导完成标记)
            if (status.allDone && status.boostExists) {
                Spacer(Modifier.height(ArcoSpacing.xs))
                Text(strings.agentBoostHint, fontSize = 11.sp, color = ArcoColors.Orange6)
            }
        }
    }
}

/** 单行打勾: 完成 = 绿色对勾, 未完成 = 灰色空圈。 */
@Composable
private fun BoostCheckRow(done: Boolean, label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = ArcoColors.Green6)
        } else {
            Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
        }
        Spacer(Modifier.width(ArcoSpacing.sm))
        Text(label, fontSize = 13.sp,
            color = if (done) ThemeColors.textPrimary else ThemeColors.textSecondary)
    }
}

/**
 * 引导进度检查结果 — 数据与 UI 分离, 便于 JVM 单测。
 */
internal data class AgentBoostStatus(
    val identity: Boolean,
    val avatar: Boolean,
    val theme: Boolean,
    val soul: Boolean,
    /** boost.md 存在 = 引导流程仍在进行 (不存在 = 已完成引导)。 */
    val boostExists: Boolean
) {
    val doneCount: Int get() = listOf(identity, avatar, theme, soul).count { it }
    val allDone: Boolean get() = doneCount == 4

    /** 缺失项名称 (按 身份/头像/主题/灵魂 顺序)。 */
    fun missingLabels(identityLabel: String, avatarLabel: String, themeLabel: String, soulLabel: String): List<String> =
        buildList {
            if (!identity) add(identityLabel)
            if (!avatar) add(avatarLabel)
            if (!theme) add(themeLabel)
            if (!soul) add(soulLabel)
        }

    companion object {
        /** 读取当前 Agent 引导进度 — 进入设置页时调用一次, 不实时轮询。 */
        fun check(agentName: String): AgentBoostStatus {
            val agentDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, agentName)
            val profile = java.io.File(agentDir, "profile.md")
            return AgentBoostStatus(
                identity = profile.exists() && profileHasName(profile),
                avatar = java.io.File(agentDir, "avatar.png").exists(),
                theme = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "theme.md").exists(),
                soul = java.io.File(agentDir, "soul.md").exists(),
                boostExists = java.io.File(agentDir, "boost.md").exists()
            )
        }

        /**
         * profile.md 是否已填写名字 — 兼容两种格式:
         * 模板格式 `- **名字：**` (用户手填, 全角冒号) 与 AgentProfile 格式 `- 名称: xxx` (程序写入)。
         * 只认以 "- " 开头的行, 冒号后剥离 markdown 装饰后非空即视为已填。
         */
        private fun profileHasName(profile: java.io.File): Boolean {
            return try {
                profile.readText(Charsets.UTF_8).lineSequence().any { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("-")) return@any false
                    val colon = trimmed.indexOfFirst { it == ':' || it == '：' }
                    if (colon < 0) return@any false
                    val key = trimmed.substring(0, colon).trim().trimStart('-', '*', ' ')
                    val value = trimmed.substring(colon + 1)
                        .trim()
                        .trim('*', ' ', '（', '）', '(', ')', '。', '，', ',', '、', '；', ';')
                    (key.contains("名字") || key.contains("名称")) && value.isNotBlank()
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
