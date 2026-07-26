// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mengpaw.browser.data.SearchEngine

/** Search engine logo using SVG drawable resources. */
@Composable
fun SearchEngineLogo(engine: SearchEngine, size: Int = 32, dimmed: Boolean = false) {
    val resId = when (engine) {
        SearchEngine.GOOGLE -> com.mengpaw.browser.R.drawable.ic_engine_google
        SearchEngine.BING -> com.mengpaw.browser.R.drawable.ic_engine_bing
        SearchEngine.BAIDU -> com.mengpaw.browser.R.drawable.ic_engine_baidu
        SearchEngine.DUCKDUCKGO -> com.mengpaw.browser.R.drawable.ic_engine_duckduckgo
    }
    Image(
        painter = painterResource(id = resId),
        contentDescription = engine.label,
        modifier = Modifier.size(size.dp).then(if (dimmed) Modifier.alpha(0.4f) else Modifier.alpha(1f))
    )
}
