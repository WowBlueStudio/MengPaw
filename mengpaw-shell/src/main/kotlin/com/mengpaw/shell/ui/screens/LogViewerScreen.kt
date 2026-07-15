package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Execution log viewer — shows agent command history and results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onNavigateBack: () -> Unit) {
    val logDir = remember { File("/data/data/com.mengpaw/files/logs") }
    var logFiles by remember { mutableStateOf(logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var logContent by remember { mutableStateOf("") }

    fun loadLog(name: String) {
        selectedFile = name
        logContent = try { File(logDir, name).readText() } catch (e: Exception) { "无法读取日志" }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = ArcoColors.Blue6, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text("执行日志", fontWeight = FontWeight.SemiBold)
            }},
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }},
            actions = {
                IconButton(onClick = {
                    logDir.listFiles()?.forEach { it.delete() }
                    logFiles = emptyList()
                    selectedFile = null
                }) { Icon(Icons.Default.Delete, "清空") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary))
    }) { padding ->
        if (selectedFile != null) {
            // Log content view
            Column(Modifier.fillMaxSize().padding(padding)) {
                Surface(color = ArcoColors.Gray1, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp).clickable { selectedFile = null }, tint = ArcoColors.Blue6)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text(selectedFile!!, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    }
                }
                LazyColumn(Modifier.fillMaxSize().padding(ArcoSpacing.md)) {
                    item {
                        Text(logContent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = ArcoSpacing.sm))
                    }
                }
            }
        } else {
            // Log list
            if (logFiles.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(64.dp), tint = ArcoColors.Gray4)
                    Spacer(Modifier.height(ArcoSpacing.md))
                    Text("暂无日志", style = MaterialTheme.typography.bodyLarge, color = ArcoColors.TextSecondary)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = ArcoSpacing.lg), contentPadding = PaddingValues(vertical = ArcoSpacing.md), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    items(logFiles) { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(ArcoRadius.lg),
                            colors = CardDefaults.cardColors(containerColor = ArcoColors.BgPrimary),
                            onClick = { loadLog(file.name) }
                        ) {
                            Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Description, null, tint = ArcoColors.Blue6, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(ArcoSpacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(formatSize(file.length()), style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long) = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
