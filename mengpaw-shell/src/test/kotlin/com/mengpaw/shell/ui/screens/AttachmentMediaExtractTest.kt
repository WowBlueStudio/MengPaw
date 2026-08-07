// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.typeFromMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 气泡媒体提取纯逻辑测试 (extractMedia + typeFromMime)。
 *
 * 提取规则: ![alt](path) markdown 图片、[name](path) 媒体扩展名链接、
 * "Saved to/已保存到" 行 → 卡片; data:/javascript: 前缀排除;
 * 本地路径须存在才提取 (保守), http(s) URL 按扩展名判定。
 * 全部为纯 Kotlin (正则 + File 判存在), 可脱离 Compose 直接测试。
 */
class AttachmentMediaExtractTest {

    // ── typeFromMime 分类 ──

    @Test
    fun mime分类_图像() {
        assertEquals("image", typeFromMime("image/png"))
        assertEquals("image", typeFromMime("image/jpeg"))
        assertEquals("image", typeFromMime(null, "a.png"))
        assertEquals("image", typeFromMime(null, "photo.JPG"))
    }

    @Test
    fun mime分类_音频视频文档() {
        assertEquals("audio", typeFromMime("audio/mpeg"))
        assertEquals("audio", typeFromMime(null, "voice.m4a"))
        assertEquals("video", typeFromMime("video/mp4"))
        assertEquals("video", typeFromMime(null, "clip.webm"))
        assertEquals("document", typeFromMime("application/pdf"))
        assertEquals("document", typeFromMime("text/plain"))
        assertEquals("document", typeFromMime(null, "note.md"))
        assertEquals("document", typeFromMime(null, "report.docx"))
    }

    @Test
    fun mime分类_未知类型() {
        assertEquals("file", typeFromMime(null, "data.xyz"))
        assertEquals("file", typeFromMime("application/octet-stream", "bin"))
    }

    // ── extractMedia: markdown 图片 ──

    @Test
    fun 本地存在的图片被提取为卡片() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            img.writeBytes(byteArrayOf(1, 2, 3, 4)) // 非空文件, 验证 size 取自真实长度
            val content = "看这张图: ![示例图](${img.absolutePath}) 后续文字"
            val (clean, cards) = extractMedia(content)
            assertFalse("图片链接应从正文移除", clean.contains("!["))
            assertTrue("卡片列表应有 1 项", cards.size == 1)
            assertEquals("image", cards[0].type)
            assertEquals(img.absolutePath, cards[0].path)
            assertEquals("image/png", cards[0].mimeType)
            assertTrue("本地文件应带真实大小", cards[0].size > 0)
        } finally {
            img.delete()
        }
    }

    @Test
    fun 本地不存在的图片不提取_正文保留() {
        val ghost = "${Files.createTempDirectory("ghost-").toFile().absolutePath}${java.io.File.separator}no.png"
        val content = "![x]($ghost)"
        val (clean, cards) = extractMedia(content)
        assertEquals(0, cards.size)
        assertTrue("提取失败时原文保留", clean.contains("![x]"))
    }

    @Test
    fun 远程URL图片按扩展名提取无需存在() {
        val (clean, cards) = extractMedia("![banner](https://example.com/banner.png)")
        assertEquals(1, cards.size)
        assertEquals("image", cards[0].type)
        assertEquals("https://example.com/banner.png", cards[0].path)
        assertFalse(clean.contains("!["))
    }

    @Test
    fun data前缀排除_防注入() {
        val content = "![x](data:image/png;base64,iVBORw0KGgo=)"
        val (clean, cards) = extractMedia(content)
        assertEquals(0, cards.size)
        assertTrue("data: 链接原样保留", clean.contains("data:"))
    }

    @Test
    fun javascript前缀排除() {
        val content = "![x](javascript:alert(1))"
        val (clean, cards) = extractMedia(content)
        assertEquals(0, cards.size)
        assertTrue(clean.contains("javascript:"))
    }

    @Test
    fun 多张图片全部提取() {
        val img1 = Files.createTempFile("media1", ".png").toFile()
        val img2 = Files.createTempFile("media2", ".png").toFile()
        try {
            val content = "![一](${img1.absolutePath}) 与 ![二](${img2.absolutePath})"
            val (clean, cards) = extractMedia(content)
            assertEquals(2, cards.size)
            assertEquals(img1.absolutePath, cards[0].path)
            assertEquals(img2.absolutePath, cards[1].path)
            assertFalse(clean.contains("!["))
        } finally {
            img1.delete(); img2.delete()
        }
    }

    // ── extractMedia: [name](path) 链接 ──

    @Test
    fun 链接形式媒体被提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val content = "[下载图片](${img.absolutePath}) 说明文字"
            val (clean, cards) = extractMedia(content)
            assertEquals(1, cards.size)
            assertEquals("image", cards[0].type)
            assertEquals("下载图片", cards[0].name)
            assertFalse("链接应从正文移除", clean.contains("]("))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 非媒体扩展名链接不提取() {
        val content = "[配置](config.xyz) 保持原样"
        val (clean, cards) = extractMedia(content)
        assertEquals(0, cards.size)
        assertTrue("非媒体链接保留", clean.contains("[配置](config.xyz)"))
    }

    // ── extractMedia: Saved to / 已保存到 行 ──

    @Test
    fun 已保存到行被提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val content = "任务完成。\n已保存到 ${img.absolutePath}\n下次再见"
            val (clean, cards) = extractMedia(content)
            assertEquals(1, cards.size)
            assertEquals("image", cards[0].type)
            assertFalse("该行应从正文移除", clean.contains("已保存到"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 空内容与普通文本无卡片() {
        val (clean1, cards1) = extractMedia("")
        assertEquals(0, cards1.size)
        assertEquals("", clean1)
        val (clean2, cards2) = extractMedia("这是一段普通文字，没有附件。")
        assertEquals(0, cards2.size)
        assertTrue(clean2.contains("普通文字"))
    }

    // ── extractMedia: 交付行容错 (v0.34.0+ 格式漂移加固) ──

    @Test
    fun 交付动词带冒号_半角全角均提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val half = extractMedia("任务完成。\nSaved to: ${img.absolutePath}")
            assertEquals("半角冒号应提取", 1, half.second.size)
            val full = extractMedia("任务完成。\n已保存到：${img.absolutePath}")
            assertEquals("全角冒号应提取", 1, full.second.size)
        } finally {
            img.delete()
        }
    }

    @Test
    fun saved_to小写提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val (clean, cards) = extractMedia("saved to ${img.absolutePath}")
            assertEquals(1, cards.size)
            assertFalse(clean.contains("saved"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 引号包裹路径_含空格提取() {
        val img = Files.createTempFile("media with space", ".png").toFile()
        try {
            val content = "已保存到 \"${img.absolutePath}\""
            val (clean, cards) = extractMedia(content)
            assertEquals(1, cards.size)
            assertEquals(img.absolutePath, cards[0].path)
            assertFalse(clean.contains("已保存到"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 独立行纯路径提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            // Android 路径为前斜杠形态 (Windows 测试环境用 replace 模拟)
            val fwdPath = img.absolutePath.replace('\\', '/')
            val (clean, cards) = extractMedia("生成完毕\n$fwdPath\n还有什么需要吗？")
            assertEquals(1, cards.size)
            assertEquals(fwdPath, cards[0].path)
            assertFalse("独立路径行应从正文移除", clean.contains("media"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 交付动词_文件在_提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val (clean, cards) = extractMedia("图片文件在 ${img.absolutePath}。")
            assertEquals(1, cards.size)
            assertEquals("image", cards[0].type)
            assertFalse(clean.contains("文件在"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 路径后跟中文文本_不误提取_原文保留() {
        // 中文尾随文本无空格 — 扩展名白名单限定捕获, 不命中即保留原文 (不静默丢失)
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val content = "图片文件在 ${img.absolutePath}，请查收"
            val (clean, cards) = extractMedia(content)
            assertEquals(0, cards.size)
            assertTrue("尾随中文文本时原文保留", clean.contains("文件在"))
        } finally {
            img.delete()
        }
    }

    @Test
    fun file链接前缀提取() {
        val img = Files.createTempFile("media", ".png").toFile()
        try {
            val content = "[查看图片](file://${img.absolutePath})"
            val (clean, cards) = extractMedia(content)
            assertEquals(1, cards.size)
            assertEquals("image", cards[0].type)
            assertFalse(clean.contains("]("))
        } finally {
            img.delete()
        }
    }

    @Test
    fun 非媒体扩展名交付行不提取_原文保留() {
        val dir = Files.createTempDirectory("media-xyz-").toFile()
        try {
            val ghost = "${dir.absolutePath}${java.io.File.separator}config.xyz"
            val (clean, cards) = extractMedia("已保存到 $ghost")
            assertEquals(0, cards.size)
            assertTrue("非媒体交付行保留原文", clean.contains("config.xyz"))
        } finally {
            dir.delete()
        }
    }
}
