// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Arco-style Card component.
 */
@Composable
fun ArcoCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(ArcoSpacing.lg)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = com.mengpaw.design.theme.ThemeColors.textPrimary
                )
            }
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(ArcoSpacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = com.mengpaw.design.theme.ThemeColors.textSecondary
                )
            }
            if (title != null || subtitle != null) {
                Spacer(modifier = Modifier.height(ArcoSpacing.md))
            }
            content()
        }
    }
}

/**
 * Arco-style Badge component.
 */
@Composable
fun ArcoBadge(
    text: String,
    color: Color = ArcoColors.Blue6,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ArcoRadius.sm))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = ArcoSpacing.sm, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Arco-style Empty state component.
 */
@Composable
fun ArcoEmpty(
    modifier: Modifier = Modifier,
    message: String = "No data",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(ArcoSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(ArcoSpacing.xxl))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = com.mengpaw.design.theme.ThemeColors.textSecondary
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(ArcoSpacing.lg))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * Arco-style Divider.
 */
@Composable
fun ArcoDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = com.mengpaw.design.theme.ThemeColors.border
    )
}
