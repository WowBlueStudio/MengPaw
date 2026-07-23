// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.tokens.ArcoSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }

    // 异步读取，避免 ANR
    LaunchedEffect(Unit) {
        text = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { ctx.resources.openRawResource(com.mengpaw.shell.R.raw.license)
                .bufferedReader().readText() } catch (_: Exception) { "" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("许可证 · AGPL-3.0", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = ArcoSpacing.lg)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(ArcoSpacing.md))
            if (text != null) com.mengpaw.design.components.MarkdownText(
                content = text!!,
                nestedScroll = true
            )
            else {
                CircularProgressIndicator(Modifier.size(24.dp).padding(top = 32.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
