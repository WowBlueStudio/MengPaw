// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.design.tokens

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Arco Design typography tokens adapted for Jetpack Compose.
 */
object ArcoTypography {
    // Font sizes matching Arco Design
    val display = 36.sp
    val title1 = 28.sp
    val title2 = 24.sp
    val title3 = 20.sp
    val body1 = 16.sp
    val body2 = 14.sp
    val body3 = 13.sp
    val caption = 12.sp

    // Line heights (as sp values - multiply by 1.5-1.8 for actual line height)
    val lineHeightBody = 22.sp
    val lineHeightTitle = 32.sp
}

/**
 * Arco Design spacing tokens.
 */
object ArcoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val section = 48.dp
}

/**
 * Arco Design border radius tokens.
 */
object ArcoRadius {
    val none = 0.dp
    val sm = 2.dp
    val md = 4.dp
    val lg = 8.dp
    val xl = 12.dp
    val round = 999.dp
}

/**
 * Arco Design shadow/elevation tokens.
 */
object ArcoShadow {
    val none = androidx.compose.ui.unit.DpOffset(0.dp, 0.dp) to 0.dp
    val sm = androidx.compose.ui.unit.DpOffset(0.dp, 2.dp) to 8.dp
    val md = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp) to 16.dp
    val lg = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp) to 24.dp
}

/**
 * Arco Design icon sizes.
 */
object ArcoIconSize {
    val sm = 14.dp
    val md = 16.dp
    val lg = 18.dp
    val xl = 24.dp
}
