// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType

/** Shared Mock LLM provider for SessionManager tests (was private inside SessionManagerTest). */
internal class MockLlmProvider(
        private val onComplete: () -> String
    ) : LlmProvider {
        override suspend fun complete(prompt: String): String = onComplete()
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String = onComplete()
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = onComplete()
        override fun info(): ProviderInfo = ProviderInfo("mock", "mock-model", ProviderType.LOCAL)
        override fun close() {}
}
