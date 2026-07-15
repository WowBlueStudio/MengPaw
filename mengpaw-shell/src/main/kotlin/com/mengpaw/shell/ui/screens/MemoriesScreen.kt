package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.mengpaw.core.memory.MemoryEntry
import com.mengpaw.core.memory.MemoryManager
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    onNavigateBack: () -> Unit,
    onPreviewFile: (String, String) -> Unit = { _, _ -> }
) {
    val manager = remember { MemoryManager() }
    var memories by remember { mutableStateOf(manager.list()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var showNewDialog by remember { mutableStateOf(false) }

    fun refresh() { memories = manager.list() }

    // Install default documents + skill/tool indices on first load
    LaunchedEffect(Unit) {
        manager.installDefaults()
        // Generate skill-index from SkillManager
        val skillManager = com.mengpaw.core.skill.SkillManager()
        skillManager.installDefaults()
        val skillIndex = skillManager.generateIndex()
        if (manager.get("skill-index") == null) {
            manager.save("skill-index", "Skill 索引", skillIndex,
                listOf("reference", "skills", "index"))
        }
        refresh()
    }

    if (selectedId != null) {
        val memory = manager.get(selectedId!!)
        if (memory == null) {
            selectedId = null
        } else if (isEditing) {
            MemoryEditScreen(memory, editTitle, editContent, editTags,
                onTitleChange = { editTitle = it },
                onContentChange = { editContent = it },
                onTagsChange = { editTags = it },
                onSave = {
                    manager.save(memory.id, editTitle, editContent,
                        editTags.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    isEditing = false; refresh()
                },
                onCancel = { isEditing = false })
        } else {
            MemoryViewScreen(memory,
                onEdit = { editTitle = memory.title; editContent = memory.content; editTags = memory.tags.joinToString(", "); isEditing = true },
                onDelete = { manager.delete(memory.id); selectedId = null; refresh() },
                onBack = { selectedId = null })
        }
        return
    }

    // ─── 列表 ───
    Scaffold(topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, null, tint = ArcoColors.Blue6, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(ArcoSpacing.sm))
            Text("记忆", fontWeight = FontWeight.SemiBold)
        }},
        navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }},
        actions = { IconButton(onClick = { showNewDialog = true }) { Icon(Icons.Default.Add, "新建") }},
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary))
    }) { padding ->
        if (memories.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(64.dp), tint = ArcoColors.Gray4)
                Spacer(Modifier.height(ArcoSpacing.md))
                Text("暂无记忆", style = MaterialTheme.typography.bodyLarge, color = ArcoColors.TextSecondary)
                Spacer(Modifier.height(ArcoSpacing.sm))
                Text("点 + 创建第一条记忆", style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextDisabled)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = ArcoSpacing.lg), contentPadding = PaddingValues(vertical = ArcoSpacing.md), verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                items(memories) { mem -> MemoryCard(mem, onClick = { selectedId = mem.id }) }
            }
        }
    }

    // ─── 新建对话框 ───
    if (showNewDialog) {
        var newId by remember { mutableStateOf("") }
        var newTitle by remember { mutableStateOf("") }
        var newContent by remember { mutableStateOf("") }
        var newTags by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建记忆") },
            text = { Column {
                OutlinedTextField(value = newId, onValueChange = { newId = it }, label = { Text("ID (文件名)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(value = newContent, onValueChange = { newContent = it }, label = { Text("内容 (Markdown)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(value = newTags, onValueChange = { newTags = it }, label = { Text("标签 (逗号分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }},
            confirmButton = { TextButton(onClick = {
                if (newId.isNotBlank()) {
                    manager.save(newId, newTitle.ifBlank { newId }, newContent, newTags.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    showNewDialog = false; refresh()
                }
            }) { Text("创建") }},
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("取消") } })
    }
}

@Composable
private fun MemoryCard(memory: MemoryEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = ArcoColors.BgPrimary), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, null, tint = ArcoColors.Blue6, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(memory.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (memory.tags.isNotEmpty()) Text(memory.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
            }
            Text("${memory.content.length}字", style = MaterialTheme.typography.labelSmall, color = ArcoColors.Gray5)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryViewScreen(memory: MemoryEntry, onEdit: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(memory.title, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }},
            actions = {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
                IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Default.Delete, "删除", tint = ArcoColors.Red6) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(all = ArcoSpacing.lg)) {
            if (memory.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.xs)) {
                    memory.tags.forEach { tag ->
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Blue1) {
                            Text(tag, modifier = Modifier.padding(horizontal = ArcoSpacing.sm, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Blue6)
                        }
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.md))
            }
            Text(memory.content, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除记忆？") },
            text = { Text("此操作不可撤销。") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("删除", color = ArcoColors.Red6) }},
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryEditScreen(memory: MemoryEntry, title: String, content: String, tags: String,
    onTitleChange: (String) -> Unit, onContentChange: (String) -> Unit, onTagsChange: (String) -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("编辑记忆", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "取消") }},
            actions = { TextButton(onClick = onSave) { Text("保存", color = ArcoColors.Blue6, fontWeight = FontWeight.Bold) }},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(all = ArcoSpacing.lg)) {
            OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(ArcoSpacing.sm))
            OutlinedTextField(value = tags, onValueChange = onTagsChange, label = { Text("标签 (逗号分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(ArcoSpacing.sm))
            OutlinedTextField(value = content, onValueChange = onContentChange, label = { Text("内容 (Markdown)") }, minLines = 10, modifier = Modifier.fillMaxWidth())
        }
    }
}
