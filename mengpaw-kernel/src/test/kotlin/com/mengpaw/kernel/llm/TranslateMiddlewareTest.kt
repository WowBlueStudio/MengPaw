// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** TranslateMiddleware 纯函数测试 — targetLanguageFrom / textToTranslate (/Translate 模式依赖)。 */
class TranslateMiddlewareTest {

    @Test
    fun `target language - chinese directive maps to iso codes`() {
        assertEquals("en", TranslateMiddleware.targetLanguageFrom("翻译成英文：hello"))
        assertEquals("ja", TranslateMiddleware.targetLanguageFrom("把这段话翻译成日语，谢谢"))
        assertEquals("ko", TranslateMiddleware.targetLanguageFrom("请翻译为韩语"))
        assertEquals("fr", TranslateMiddleware.targetLanguageFrom("翻译成法语"))
        assertEquals("de", TranslateMiddleware.targetLanguageFrom("译成德语"))
        assertEquals("zh-CN", TranslateMiddleware.targetLanguageFrom("翻译成中文"))
    }

    @Test
    fun `target language - english directive maps to iso codes`() {
        assertEquals("ja", TranslateMiddleware.targetLanguageFrom("translate to Japanese"))
        assertEquals("es", TranslateMiddleware.targetLanguageFrom("translate to Spanish"))
        assertEquals("en", TranslateMiddleware.targetLanguageFrom("translate this text to English"))
        assertEquals("zh-CN", TranslateMiddleware.targetLanguageFrom("translate to Chinese"))
    }

    @Test
    fun `target language - no directive falls back to default`() {
        assertEquals("en", TranslateMiddleware.targetLanguageFrom("hello world"))
        assertEquals("zh-CN", TranslateMiddleware.targetLanguageFrom("hello world", fallback = "zh-CN"))
    }

    @Test
    fun `text to translate - strips chinese directive prefix`() {
        assertEquals("hello", TranslateMiddleware.textToTranslate("翻译成英文：hello"))
        assertEquals("你好", TranslateMiddleware.textToTranslate("翻译：你好"))
        assertEquals("こんにちは", TranslateMiddleware.textToTranslate("请把这句话翻译成日语：こんにちは"))
    }

    @Test
    fun `text to translate - strips english directive prefix`() {
        assertEquals("hello", TranslateMiddleware.textToTranslate("translate to English: hello"))
        assertEquals("bonjour", TranslateMiddleware.textToTranslate("translate this text to French: bonjour"))
    }

    @Test
    fun `text to translate - no directive returns whole task`() {
        assertEquals("hello world", TranslateMiddleware.textToTranslate("hello world"))
        assertEquals("你好世界", TranslateMiddleware.textToTranslate("你好世界"))
    }
}
