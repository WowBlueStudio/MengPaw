// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Installed app entry for the launcher grid.
 */
data class TvAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null
)

/**
 * TV app grid — horizontal scrolling row of installed launchable apps.
 */
@Composable
fun TvAppGrid(
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val apps = remember { loadLaunchableApps(ctx) }
    val focusRequester = remember { FocusRequester() }

    if (apps.isEmpty()) return

    Column(modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
        Text(
            "应用 Apps",
            fontSize = 18.sp,
            color = Color(0xFF86909C),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(apps.take(20)) { app ->
                AppCard(app = app, onClick = {
                    launchApp(ctx, app.packageName)
                })
            }
        }
    }
}

@Composable
private fun AppCard(app: TvAppEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x15FFFFFF))
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .focusable()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x20FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.label.take(2),
                fontSize = 24.sp,
                color = Color(0xFFCCCCCC)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label,
            fontSize = 14.sp,
            color = Color(0xFFCCCCCC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun loadLaunchableApps(ctx: Context): List<TvAppEntry> {
    return try {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != "com.mengpaw.tv" } // exclude self
            .map { ri ->
                TvAppEntry(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                )
            }
            .distinctBy { it.packageName }
    } catch (_: Exception) { emptyList() }
}

private fun launchApp(ctx: Context, packageName: String) {
    try {
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    } catch (_: Exception) { }
}
