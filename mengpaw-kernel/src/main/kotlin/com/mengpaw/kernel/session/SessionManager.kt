// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.session

import kotlinx.serialization.Serializable

/**
 * A single message in the conversation history.
 */
@Serializable
data class Message(
    val role: String,        // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Represents a session - a single Agent conversation.
 */
@Serializable
data class Session(
    val id: String,
    val task: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<Message> = mutableListOf(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * A checkpoint for saving and restoring Agent progress.
 */
@Serializable
data class Checkpoint(
    val sessionId: String,
    val step: Int,
    val remainingTask: String,
    val context: Map<String, String>,
    val createdAt: Long = System.currentTimeMillis()
)
