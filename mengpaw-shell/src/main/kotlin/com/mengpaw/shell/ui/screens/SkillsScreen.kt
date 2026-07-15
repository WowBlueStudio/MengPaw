package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.core.skill.Skill
import com.mengpaw.core.skill.SkillManager
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Skills screen — list, enable/disable, import skills.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onNavigateBack: () -> Unit) {
    val manager = remember { SkillManager() }
    var skills by remember { mutableStateOf(manager.list()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showInstallDefaults by remember { mutableStateOf(false) }

    fun refresh() { skills = manager.list() }

    // 首次加载默认 Skill
    LaunchedEffect(Unit) {
        manager.installDefaults()
        refresh()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, tint = ArcoColors.Blue6, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text("Skills", fontWeight = FontWeight.SemiBold)
            }},
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }},
            actions = { IconButton(onClick = { showInstallDefaults = true }) { Icon(Icons.Default.Add, "导入") }},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary))
    }) { padding ->
        if (skills.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(64.dp), tint = ArcoColors.Gray4)
                Spacer(Modifier.height(ArcoSpacing.md))
                Text("暂无 Skill", style = MaterialTheme.typography.bodyLarge, color = ArcoColors.TextSecondary)
                Spacer(Modifier.height(ArcoSpacing.sm))
                Text("点 + 安装默认 Skill", style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextDisabled)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = ArcoSpacing.lg), contentPadding = PaddingValues(vertical = ArcoSpacing.md), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                items(skills) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggle = { enabled ->
                            manager.setEnabled(skill.fileName.ifBlank { skill.name }, enabled)
                            refresh()
                        },
                        onDelete = {
                            manager.delete(skill.fileName.ifBlank { skill.name })
                            refresh()
                        }
                    )
                }
            }
        }
    }

    // 安装默认 Skill 对话框
    if (showInstallDefaults) {
        AlertDialog(
            onDismissRequest = { showInstallDefaults = false },
            title = { Text("导入 Skill") },
            text = { Text("安装搜索、总结、翻译等默认 Skill？") },
            confirmButton = { TextButton(onClick = {
                manager.installDefaults()
                showInstallDefaults = false
                refresh()
            }) { Text("安装") }},
            dismissButton = { TextButton(onClick = { showInstallDefaults = false }) { Text("取消") } })
    }

    // 手动导入对话框
    if (showImportDialog) {
        var importName by remember { mutableStateOf("") }
        var importContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("手动导入 Skill") },
            text = { Column {
                OutlinedTextField(value = importName, onValueChange = { importName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(value = importContent, onValueChange = { importContent = it }, label = { Text("内容 (Markdown)") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            }},
            confirmButton = { TextButton(onClick = {
                if (importName.isNotBlank()) {
                    val md = """---
name: $importName
description: 手动导入
enabled: true
category: custom
---
$importContent"""
                    manager.importFromText(md, "$importName.md")
                    showImportDialog = false
                    refresh()
                }
            }) { Text("导入") }},
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } })
    }
}

@Composable
private fun SkillCard(skill: Skill, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = ArcoColors.BgPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Blue1) {
                        Text(skill.category, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = ArcoColors.Blue6)
                    }
                }
                if (skill.description.isNotBlank()) {
                    Text(skill.description, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Switch(checked = skill.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Blue6))
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, "删除", tint = ArcoColors.Gray5, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除 Skill？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("删除", color = ArcoColors.Red6) }},
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } })
    }
}
