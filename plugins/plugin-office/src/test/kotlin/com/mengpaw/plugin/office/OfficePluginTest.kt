// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OfficePlugin 文档操作单元测试 — 基于 POI 在临时目录读写 docx/xlsx/pptx。 */
class OfficePluginTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val plugin = OfficePlugin()

    @Test
    fun createThenWriteThenRead_docx() {
        val path = tmp.newFile("t.docx").absolutePath
        plugin.createDocument(path, "docx")
        plugin.writeDocument(path, "Hello MengPaw")
        val content = plugin.readDocument(path)
        assertTrue("docx 应能读回写入的段落", content.contains("Hello MengPaw"))
    }

    @Test
    fun createThenWriteThenRead_xlsx() {
        val path = tmp.newFile("t.xlsx").absolutePath
        plugin.createDocument(path, "xlsx")
        plugin.writeDocument(path, "Sheet1:A1:42")
        val content = plugin.readDocument(path)
        assertTrue("xlsx 应能读回 A1 单元格的数值", content.contains("42"))
    }

    @Test
    fun writeXlsx_stringCell_readBack() {
        val path = tmp.newFile("t.xlsx").absolutePath
        plugin.createDocument(path, "xlsx")
        plugin.writeDocument(path, "Sheet1:B2:报告")
        val content = plugin.readDocument(path)
        assertTrue("xlsx 应能读回 B2 字符串单元格", content.contains("报告"))
    }

    @Test
    fun createPptx_thenRead_isEmptyButValid() {
        val path = tmp.newFile("t.pptx").absolutePath
        plugin.createDocument(path, "pptx")
        // 空演示文稿可读取 (0 内容), 不抛异常即可
        plugin.readDocument(path)
        assertTrue("pptx 文件应已创建", java.io.File(path).length() > 0)
    }

    @Test
    fun writePptx_throws_javaAwtLimit() {
        val path = tmp.newFile("t.pptx").absolutePath
        plugin.createDocument(path, "pptx")
        try {
            plugin.writeDocument(path, "排版")
            fail("pptx write 应因 java.awt 限制抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue("应提示 pptx 限制", e.message.orEmpty().contains("java.awt"))
        }
    }

    @Test
    fun unsupportedFormat_read_throws() {
        val path = tmp.newFile("t.txt").absolutePath
        try {
            plugin.readDocument(path)
            fail("不支持格式应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("仅 docx/xlsx/pptx"))
        }
    }

    @Test
    fun parseCellRef_A1() {
        assertEquals(0 to 0, plugin.parseCellRef("A1"))
        assertEquals(1 to 1, plugin.parseCellRef("B2"))
        assertEquals(9 to 26, plugin.parseCellRef("AA10"))
    }

    @Test
    fun parseCellRef_invalid_throws() {
        try {
            plugin.parseCellRef("1A")
            fail("非法引用应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("A1"))
        }
    }
}
