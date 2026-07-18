package com.mengpaw.shell.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.components.ArcoDivider
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.MAX_CONTENT_WIDTH
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    strings: com.mengpaw.shell.ui.localization.AppStrings = com.mengpaw.shell.ui.localization.EnglishStrings,
    settingsViewModel: SettingsViewModel? = null,
    viewModel: AgentViewModel = viewModel(),
    pluginViewModel: PluginViewModel = viewModel(),
    onOpenSidebar: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val inputEnabled by viewModel.inputEnabled.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()
    var showExpandSheet by remember { mutableStateOf(false) }

    val settingsState by settingsViewModel?.state?.collectAsState() ?: remember { mutableStateOf(null) }
    LaunchedEffect(settingsState) {
        settingsState?.let { s ->
            viewModel.configureLlm(s.apiEndpoint, s.apiKey, s.modelName, s.useSimulatedProvider)
        }
    }

    var agentName by remember { mutableStateOf("MengPaw") }
    var showHistory by remember { mutableStateOf(false) }
    var showNewChatConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // ── Dialogs ──
    if (showHistory) {
        AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("历史会话") },
            text = { Text("暂无历史会话", color = ThemeColors.textSecondary, modifier = Modifier.padding(16.dp)) },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } })
    }
    if (showNewChatConfirm) {
        AlertDialog(onDismissRequest = { showNewChatConfirm = false }, title = { Text("新建会话") },
            text = { Text("当前会话将被清除，确定创建新会话？") },
            confirmButton = { TextButton(onClick = { viewModel.newSession(); showNewChatConfirm = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showNewChatConfirm = false }) { Text("取消") } })
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH.dp)) {
            // ── Header bar ── (unchanged)
            Surface(tonalElevation = 1.dp, color = ThemeColors.bgPrimary) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Sidebar toggle
                    IconButton(onClick = onOpenSidebar, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Menu, "侧边栏", tint = ThemeColors.textSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    // Agent avatar
                    val avatarFile = File(com.mengpaw.core.DataPaths.AGENTS, "avatar.png")
                    val avatarBitmap = remember { if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null }
                    if (avatarBitmap != null) {
                        Image(bitmap = avatarBitmap.asImageBitmap(), null, Modifier.size(32.dp).clip(CircleShape))
                    } else {
                        Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.brandContainer) {
                            Text(agentName.take(1), Modifier.padding(6.dp), color = ThemeColors.brand, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Text(agentName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (isRunning) {
                        LinearProgressIndicator(Modifier.width(60.dp).height(4.dp), color = ThemeColors.brand)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                    }
                    IconButton(onClick = { showHistory = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Star, "历史", tint = ThemeColors.textSecondary)
                    }
                    IconButton(onClick = { showNewChatConfirm = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Add, "新建", tint = ThemeColors.textSecondary)
                    }
                }
            }
            ArcoDivider()

            // ── Messages ──
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = ArcoSpacing.lg),
                state = listState, verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm),
                contentPadding = PaddingValues(vertical = ArcoSpacing.md)
            ) {
                items(messages.filter { it !is ChatMessageUi.System }) { message ->
                    when (message) {
                        is ChatMessageUi.User -> UserBubble(message.content)
                        is ChatMessageUi.Agent -> AgentBubble(message.content)
                        is ChatMessageUi.Suggestion -> PluginSuggestionCard(message.suggestion,
                            onInstall = { pluginViewModel.installPlugin(message.suggestion.pluginId) },
                            onViewDetail = onNavigateToPlugins)
                        else -> {}
                    }
                }
            }

            // ── Bottom input bar (clean: input + send + expand) ──
            Surface(shadowElevation = 8.dp, color = ThemeColors.bgPrimary) {
                Row(Modifier.fillMaxWidth().padding(start = ArcoSpacing.lg, end = 8.dp, bottom = ArcoSpacing.lg, top = ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Expand button (left of input)
                    IconButton(onClick = { showExpandSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.AddCircle, "扩展", tint = ThemeColors.textSecondary, modifier = Modifier.size(24.dp))
                    }
                    // Input field
                    OutlinedTextField(value = inputText, onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp), enabled = inputEnabled,
                        placeholder = { Text(strings.inputPlaceholder) },
                        shape = RoundedCornerShape(ArcoRadius.lg),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ThemeColors.brand, unfocusedBorderColor = ThemeColors.border),
                        minLines = 1, maxLines = 4)
                    // Send button
                    FilledIconButton(onClick = {
                        if (inputText.isNotBlank()) { viewModel.submitTask(inputText, pluginViewModel); inputText = "" }
                    }, enabled = inputEnabled, shape = RoundedCornerShape(ArcoRadius.lg),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ThemeColors.brand),
                        modifier = Modifier.size(44.dp))
                    { Icon(Icons.Default.Send, "发送", tint = Color.White) }
                }
            }
        }
    }

    // ── Expand bottom sheet (upload tools + plugins) ──
    if (showExpandSheet) {
        ModalBottomSheet(onDismissRequest = { showExpandSheet = false }, sheetState = sheetState,
            containerColor = ThemeColors.bgPrimary) {
            Column(Modifier.padding(ArcoSpacing.lg).padding(bottom = 32.dp)) {
                Text("扩展功能", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.lg))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ExpandItem(Icons.Outlined.Image, "图片") {}
                    ExpandItem(Icons.Outlined.Description, "文档") {}
                    ExpandItem(Icons.Outlined.AttachFile, "文件") {}
                    ExpandItem(Icons.Outlined.PhotoCamera, "拍照") {}
                }

                Spacer(Modifier.height(24.dp))
                Text("插件工具", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.sm))

                val installed = pluginViewModel.pluginItems.collectAsState().value.filter { it.isActive }
                if (installed.isEmpty()) {
                    Text("暂无已激活插件。在插件管理中安装并启用。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                } else {
                    installed.take(6).forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { showExpandSheet = false }.padding(vertical = ArcoSpacing.sm)) {
                            Icon(Icons.Outlined.Extension, null, Modifier.size(20.dp), tint = ThemeColors.brand)
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.lg))
            }
        }
    }
}

@Composable
private fun ExpandItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.bgCardHigh,
            modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = ThemeColors.textSecondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    }
}

// ── Plugin suggestion card ──
@Composable
fun PluginSuggestionCard(suggestion: PluginSuggestion, onInstall: () -> Unit, onViewDetail: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = ArcoColors.Orange1.copy(alpha = 0.3f))) {
        Column(Modifier.padding(ArcoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, tint = ArcoColors.Orange6, modifier = Modifier.size(20.dp))
                Text("需要安装插件", fontWeight = FontWeight.SemiBold, color = ArcoColors.Orange6, style = MaterialTheme.typography.bodySmall)
            }
            Text("${suggestion.pluginName} (${suggestion.pluginId})", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
            Spacer(Modifier.height(ArcoSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                Button(onClick = onInstall, shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs),
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand))
                { Text("一键安装", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(onClick = onViewDetail, shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs))
                { Text("查看详情", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ── Bubbles ──
@Composable private fun UserBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm), color = ThemeColors.brand) {
            Text(content, Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun AgentBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm, ArcoRadius.lg), color = ThemeColors.bgCardHigh) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                Text("MengPaw", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
                Spacer(Modifier.height(ArcoSpacing.xs))
                Text(content, color = ThemeColors.textPrimary, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
