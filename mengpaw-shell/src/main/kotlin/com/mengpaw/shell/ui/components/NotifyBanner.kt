// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.namespace.NotifyBus
import com.mengpaw.kernel.namespace.NotifyBus.NotifyLevel
import kotlinx.coroutines.launch

/**
 * Collects Agent-pushed notifications from [NotifyBus] and renders:
 * - BANNER: animated overlay that auto-dismisses after a few seconds.
 *   Clicking the banner invokes [onBannerClick] before dismissing,
 *   allowing navigation to the relevant agent session.
 * - MESSAGE: injected into the parent chat message list
 */
@Composable
fun NotifyBannerHost(
    onMessage: ((String) -> Unit)? = null,
    onBannerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var currentBanner by remember { mutableStateOf<NotifyBus.NotifyEvent?>(null) }
    var bannerVisible by remember { mutableStateOf(false) }

    // Collect events
    LaunchedEffect(Unit) {
        NotifyBus.events.collect { event ->
            when (event.type) {
                NotifyBus.NotifyType.MESSAGE -> {
                    onMessage?.invoke(event.text)
                }
                NotifyBus.NotifyType.BANNER -> {
                    currentBanner = event
                    bannerVisible = true
                }
            }
        }
    }

    // Auto-dismiss banner
    if (bannerVisible) {
        LaunchedEffect(currentBanner) {
            kotlinx.coroutines.delay(4000)
            bannerVisible = false
        }
    }

    // Banner overlay
    AnimatedVisibility(
        visible = bannerVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        currentBanner?.let { banner ->
            val (bg, fg, icon) = when (banner.level) {
                NotifyLevel.INFO -> Triple(ArcoColors.Blue1, ArcoColors.Blue6, Icons.Outlined.Info)
                NotifyLevel.SUCCESS -> Triple(ArcoColors.Green1, ArcoColors.Green6, Icons.Outlined.CheckCircle)
                NotifyLevel.WARN -> Triple(ArcoColors.Orange1, ArcoColors.Orange6, Icons.Outlined.Warning)
                NotifyLevel.ERROR -> Triple(ArcoColors.Red1, ArcoColors.Red6, Icons.Outlined.Error)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs)
                    .clickable {
                        onBannerClick?.invoke()
                        bannerVisible = false
                    },
                shape = RoundedCornerShape(ArcoRadius.lg),
                color = bg,
                shadowElevation = 4.dp
            ) {
                Row(
                    Modifier.padding(ArcoSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = fg)
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Text(
                        banner.text,
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = fg,
                        maxLines = 3
                    )
                    Text(
                        "✕",
                        fontSize = 12.sp,
                        color = fg.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { bannerVisible = false }
                    )
                }
            }
        }
    }
}
