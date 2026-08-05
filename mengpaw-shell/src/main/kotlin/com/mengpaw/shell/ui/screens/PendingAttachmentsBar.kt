// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.screens.model.InputTag
import kotlin.random.Random

/**
 * 待发栏 (v0.34.0+) — 输入栏容器内、输入框顶部的一行统一待发区。
 *
 * 元素靠左 FlowRow 换行: 斜杠命令/@agent 标签在前, 附件块按添加顺序在后。
 * 图片 → 原比例缩略图 (最大高度 40dp) + 右上角关闭; 音频 → 语音条样式块;
 * 文档/视频/文件 → 图标+文件名名称块 + 右侧关闭。
 * 无内容时整行隐藏 (AnimatedVisibility 由调用方控制)。
 * 附件/标签不同步进输入框文本 — 发送时随消息结构化提交。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PendingAttachmentsBar(
    activeTags: List<InputTag>,
    attachments: List<AttachmentData>,
    strings: AppStrings,
    onRemoveTag: (InputTag) -> Unit,
    onRemoveAttachment: (AttachmentData) -> Unit
) {
    FlowRow(
        Modifier.fillMaxWidth()
            .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(ArcoSpacing.xs)
    ) {
        // ── 斜杠命令 / @agent 标签 (保留 AssistChip 样式) ──
        activeTags.forEach { tag ->
            val chipLabel = when (tag) {
                is InputTag.Mode -> tag.mode.prefix
                is InputTag.AgentRef -> "@${tag.agentName}"
            }
            AssistChip(
                onClick = {},
                label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, strings.tagDismiss,
                        Modifier.size(14.dp).clickable { onRemoveTag(tag) })
                },
                shape = RoundedCornerShape(ArcoRadius.sm),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = ThemeColors.brandContainer,
                    labelColor = ThemeColors.brand
                )
            )
        }
        // ── 附件块 (按添加顺序) ──
        attachments.forEach { att ->
            when (att.type) {
                "image" -> PendingImageBlock(att, onRemoveAttachment)
                "audio" -> PendingAudioBlock(att, onRemoveAttachment)
                else -> PendingFileBlock(att, onRemoveAttachment)
            }
        }
    }
}

/** 图片缩略图块 — 原比例, 最大高度 40dp, 右上角关闭。 */
@Composable
private fun PendingImageBlock(att: AttachmentData, onRemove: (AttachmentData) -> Unit) {
    val bitmap = remember(att.path) {
        if (att.path.startsWith("http")) null else decodeSampled(att.path, maxDim = 512)
    }
    Box(
        Modifier.height(40.dp).widthIn(min = 48.dp, max = 64.dp)
            .clip(RoundedCornerShape(ArcoRadius.sm))
            .background(ThemeColors.surfaceContainerHigh)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = att.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            // http URL 或解码失败 → 名称块回退 (待发附件均为本地拷贝, 防御分支)
            Icon(attachmentTypeIcon("image"), null, tint = ThemeColors.textSecondary,
                modifier = Modifier.align(Alignment.Center).size(20.dp))
        }
        // 右上角关闭
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .clickable { onRemove(att) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, "移除附件", tint = Color.White, modifier = Modifier.size(10.dp))
        }
    }
}

/** 语音条块 — 波形装饰 + 文件名 + 右侧关闭。 */
@Composable
private fun PendingAudioBlock(att: AttachmentData, onRemove: (AttachmentData) -> Unit) {
    val waveHeights = remember(att.path) { List(10) { (8 + Random.nextInt(13)).dp } }
    PendingBlockBase(onRemove = { onRemove(att) }) {
        // 波形装饰 (静态, 对齐气泡内语音卡片语言)
        Row(Modifier.height(18.dp).padding(end = 4.dp), verticalAlignment = Alignment.Bottom) {
            waveHeights.forEach { h ->
                Box(Modifier.width(2.5.dp).height(h).padding(end = 1.dp)
                    .background(ThemeColors.brand.copy(alpha = 0.55f), RoundedCornerShape(1.dp)))
            }
        }
        Text(
            att.name.ifBlank { att.path.substringAfterLast('/') },
            style = MaterialTheme.typography.labelSmall,
            color = ThemeColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp)
        )
    }
}

/** 文档/视频/文件名称块 — 类型图标 + 文件名 + 右侧关闭。 */
@Composable
private fun PendingFileBlock(att: AttachmentData, onRemove: (AttachmentData) -> Unit) {
    PendingBlockBase(onRemove = { onRemove(att) }) {
        Icon(attachmentTypeIcon(att.type), null, tint = ThemeColors.textSecondary, modifier = Modifier.size(16.dp))
        Text(
            att.name.ifBlank { att.path.substringAfterLast('/') },
            style = MaterialTheme.typography.labelSmall,
            color = ThemeColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp)
        )
    }
}

/** 块底座 — surfaceContainerHigh 圆角底 + 内容 Row + 右侧 14dp 关闭。 */
@Composable
private fun PendingBlockBase(
    onRemove: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier.height(40.dp)
            .clip(RoundedCornerShape(ArcoRadius.sm))
            .background(ThemeColors.surfaceContainerHigh)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        content()
        Icon(
            Icons.Filled.Close, "移除附件",
            tint = ThemeColors.textSecondary,
            modifier = Modifier.size(14.dp).clickable(onClick = onRemove)
        )
    }
}
