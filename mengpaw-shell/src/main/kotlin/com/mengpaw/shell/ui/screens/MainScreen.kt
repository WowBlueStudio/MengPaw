package com.mengpaw.shell.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.components.ArcoDivider
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Main Agent chat interface screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToMemories: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    strings: com.mengpaw.shell.ui.localization.AppStrings = com.mengpaw.shell.ui.localization.EnglishStrings,
    viewModel: AgentViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val inputEnabled by viewModel.inputEnabled.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Sync system banner when language changes
    LaunchedEffect(strings.systemBanner) {
        viewModel.setBanner(strings.systemBanner)
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Android,
                            contentDescription = null,
                            tint = ArcoColors.Blue6,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text(
                            strings.appName,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isRunning) {
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            LinearProgressIndicator(
                                modifier = Modifier.width(80.dp).height(4.dp),
                                color = ArcoColors.Blue6,
                                trackColor = ArcoColors.Gray3
                            )
                        }
                    }
                },
                actions = {
                    if (isRunning) {
                        IconButton(onClick = { viewModel.stopAgent() }) {
                            Icon(Icons.Default.Stop, contentDescription = "停止", tint = ArcoColors.Red6)
                        }
                    }
                    IconButton(onClick = onNavigateToMemories) {
                        Icon(Icons.Default.Star, contentDescription = "记忆")
                    }
                    IconButton(onClick = onNavigateToSkills) {
                        Icon(Icons.Default.Star, contentDescription = "技能")
                    }
                    IconButton(onClick = onNavigateToBrowser) {
                        Icon(Icons.Default.Favorite, contentDescription = "浏览器")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArcoColors.BgPrimary
                )
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = ArcoColors.BgPrimary
            ) {
                Column {
                    ArcoDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = ArcoSpacing.lg,
                                end = ArcoSpacing.lg,
                                bottom = ArcoSpacing.lg,
                                top = ArcoSpacing.sm
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            enabled = inputEnabled,
                            placeholder = { Text(strings.inputPlaceholder) },
                            shape = RoundedCornerShape(ArcoRadius.lg),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ArcoColors.Blue6,
                                unfocusedBorderColor = ArcoColors.BorderDefault
                            ),
                            minLines = 1,
                            maxLines = 4
                        )
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        FilledIconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.submitTask(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = inputEnabled,
                            shape = RoundedCornerShape(ArcoRadius.lg),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = ArcoColors.Blue6
                            )
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = ArcoSpacing.lg),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm),
            contentPadding = PaddingValues(vertical = ArcoSpacing.md)
        ) {
            items(messages) { message ->
                when (message) {
                    is ChatMessageUi.System -> SystemBanner(message.content)
                    is ChatMessageUi.User -> UserMessageBubble(message.content)
                    is ChatMessageUi.Agent -> AgentMessageBubble(message.content)
                }
            }
        }
    }
}

@Composable
private fun SystemBanner(content: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = ArcoColors.Blue1)
    ) {
        Row(
            modifier = Modifier.padding(ArcoSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                tint = ArcoColors.Blue6,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(ArcoSpacing.md))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = ArcoColors.TextSecondary
            )
        }
    }
}

@Composable
private fun UserMessageBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = ArcoRadius.lg,
                topEnd = ArcoRadius.lg,
                bottomStart = ArcoRadius.lg,
                bottomEnd = ArcoRadius.sm
            ),
            color = ArcoColors.Blue6
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(
                    horizontal = ArcoSpacing.lg,
                    vertical = ArcoSpacing.md
                ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AgentMessageBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = ArcoRadius.lg,
                topEnd = ArcoRadius.lg,
                bottomStart = ArcoRadius.sm,
                bottomEnd = ArcoRadius.lg
            ),
            color = ArcoColors.Gray2
        ) {
            Column(modifier = Modifier.padding(ArcoSpacing.lg)) {
                Text(
                    text = "助手",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ArcoColors.Blue6
                )
                Spacer(Modifier.height(ArcoSpacing.xs))
                Text(
                    text = content,
                    color = ArcoColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
