// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * 使用指南面板 (v0.36) — 兑现系统提示词「教程在设置中 — USB调试/Root/无障碍指南」声明。
 * 三个指南条目各自打开 Dialog, 内容为 assets/guides/{zh|en}/ 目录下的 md 文件, 中英双语随 UI 语言切换。
 */
@Composable
internal fun DeviceGuidesPanel(strings: AppStrings, useChinese: Boolean) {
    var activeGuide by remember { mutableStateOf<GuideId?>(null) }

    SectionHeader(strings.guideSection)
    Text(strings.guideSectionDesc,
        fontSize = 11.sp, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    GuideRow(Icons.Outlined.Android, strings.guideUsb, strings.guideUsbDesc) { activeGuide = GuideId.USB }
    GuideRow(Icons.Outlined.AdminPanelSettings, strings.guideRoot, strings.guideRootDesc) { activeGuide = GuideId.ROOT }
    GuideRow(Icons.Outlined.AccessibilityNew, strings.guideAccessibility, strings.guideAccessibilityDesc) { activeGuide = GuideId.ACCESSIBILITY }

    activeGuide?.let { guide ->
        val context = LocalContext.current
        val content = remember(guide, useChinese) { loadGuide(context, guide.asset, useChinese) }
        AlertDialog(
            onDismissRequest = { activeGuide = null },
            title = { Text(guide.title(strings), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = {
                if (content.isBlank()) {
                    Text("(指南加载失败)", fontSize = 13.sp, color = ThemeColors.textSecondary)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MarkdownText(content = content)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeGuide = null }) { Text(strings.guideClose) }
            }
        )
    }
}

/** 指南条目行 — 图标 + 标题 + 副标题, 点击打开对应指南。 */
@Composable
private fun GuideRow(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ArcoSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = ArcoColors.Gray6)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }
    }
}

/** 指南资源标识 — asset 文件名与 UI 标题映射。 */
private enum class GuideId(val asset: String, val title: (AppStrings) -> String) {
    USB("usb_debugging", { it.guideUsb }),
    ROOT("root", { it.guideRoot }),
    ACCESSIBILITY("accessibility", { it.guideAccessibility });
}

/** 读取 assets/guides/{zh|en}/{asset}.md; 失败返回空串（Dialog 内兜底提示）。 */
private fun loadGuide(context: android.content.Context, asset: String, useChinese: Boolean): String {
    val lang = if (useChinese) "zh" else "en"
    return try {
        context.assets.open("guides/$lang/$asset.md")
            .bufferedReader().use { it.readText() }
    } catch (_: Exception) { "" }
}
