// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.tv

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Simple chat message for TV display.
 */
data class TvChatMessage(
    val role: String,  // "user" or "agent"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * TV-optimized chat screen.
 * Large fonts, high contrast, D-pad scrollable.
 */
@Composable
fun TvChatScreen(
    messages: List<TvChatMessage>,
    isListening: Boolean,
    partialText: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier.fillMaxSize()) {
        // Messages area
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "按住遥控器语音键开始对话\nPress voice button to speak",
                            fontSize = 22.sp,
                            color = Color(0xFF86909C),
                            lineHeight = 36.sp
                        )
                    }
                }
            }
            items(messages) { msg ->
                TvChatBubble(message = msg)
            }

            // Show partial recognition text
            if (partialText.isNotBlank()) {
                item {
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = if (partialText.startsWith("...")) Alignment.CenterEnd else Alignment.Center
                    ) {
                        Text(
                            partialText,
                            fontSize = 20.sp,
                            color = Color(0xFF86909C),
                            modifier = Modifier
                                .background(Color(0x18FFFFFF), RoundedCornerShape(12.dp))
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }

        // Listening indicator
        if (isListening) {
            Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "🎤 正在听... Speak now",
                    fontSize = 20.sp,
                    color = Color(0xFF165DFF)
                )
            }
        }
    }
}

@Composable
private fun TvChatBubble(message: TvChatMessage) {
    val isAgent = message.role == "agent"
    val alignment = if (isAgent) Alignment.Start else Alignment.End
    val bgColor = if (isAgent) Color(0x20FFFFFF) else Color(0x40165DFF)
    val textColor = if (isAgent) Color(0xFFE8E8E8) else Color(0xFFB8D4FF)

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isAgent) Alignment.Start else Alignment.End
    ) {
        Text(
            if (isAgent) "MengPaw" else "你",
            fontSize = 16.sp,
            color = Color(0xFF86909C),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            Modifier
                .widthIn(max = 700.dp)
                .background(bgColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                message.content,
                fontSize = 22.sp,
                color = textColor,
                lineHeight = 34.sp
            )
        }
    }
}
