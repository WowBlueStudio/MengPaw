// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.office

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.CommandKeywords
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File

/**
 * Office 文档编辑插件 — Word(docx)/Excel(xlsx)/PowerPoint(pptx) 的创建、读取、写入。
 *
 * 基于 Apache POI (XWPFDocument / XSSFWorkbook / XMLSlideShow)。
 *
 * ⚠️ java.awt 三铁律 (Android 无 java.awt, 触碰即 NoClassDefFoundError):
 *   1. 不调用 autoSizeColumn / 列宽自动适配 (用 java.awt FontMetrics)
 *   2. 不读写图片 (XWPFPicture/XSSFPicture 部分依赖 java.awt)
 *   3. 不碰颜色/样式 (XSSFFont.setColor 需 java.awt.Color) 与 pptx 文本框锚点 (java.awt.geom)
 *
 * 命令在 Dispatchers.IO 上执行 (POI 为 CPU 密集文件操作, 不阻塞主线程)。
 * 文件 IO 一律 try/catch; 命令被安全分级为 MID (读写本地文件, 见 CommandRiskLevels)。
 */
class OfficePlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "office-plugin",
        name = "Office 文档",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Office 文档编辑：Word(docx)/Excel(xlsx)/PowerPoint(pptx) 创建/读取/写入",
        minCoreVersion = "0.2.0",
        commands = listOf("office.read", "office.create", "office.write"),
        commandKeywords = mapOf(
            "read" to CommandKeywords(zh = listOf("读取", "文档", "word", "excel", "ppt", "office", "docx", "xlsx"), en = listOf("read", "document", "docx", "xlsx", "pptx", "office")),
            "create" to CommandKeywords(zh = listOf("创建", "新建", "文档"), en = listOf("create", "new", "document")),
            "write" to CommandKeywords(zh = listOf("写入", "编辑", "修改", "单元格"), en = listOf("write", "edit", "modify", "cell"))
        )
    )

    override val uiButtons = emptyList<com.mengpaw.kernel.plugin.PluginUiButton>()

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "read" to ::read,
        "create" to ::create,
        "write" to ::write
    )

    // ── office.read <path> ────────────────────────────────────────────────
    private suspend fun read(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: office read <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return withContext(Dispatchers.IO) {
            try {
                ExecutionResult.ok(readDocument(args[0]))
            } catch (e: Exception) {
                ExecutionResult.fail("office read 失败: ${e.message}")
            }
        }
    }

    // ── office.create <path> <docx|xlsx|pptx> ─────────────────────────────
    private suspend fun create(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: office create <path> <docx|xlsx|pptx>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return withContext(Dispatchers.IO) {
            try {
                ExecutionResult.ok(createDocument(args[0], args[1].lowercase()))
            } catch (e: Exception) {
                ExecutionResult.fail("office create 失败: ${e.message}")
            }
        }
    }

    // ── office.write <path> <content> ─────────────────────────────────────
    // docx: content 为追加段落文本; xlsx: content 为 "<sheet>:<cellRef>:<value>", 如 Sheet1:A1:Hello
    private suspend fun write(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: office write <path> <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return withContext(Dispatchers.IO) {
            try {
                ExecutionResult.ok(writeDocument(args[0], args[1]))
            } catch (e: Exception) {
                ExecutionResult.fail("office write 失败: ${e.message}")
            }
        }
    }

    // ── 实现 ──────────────────────────────────────────────────────────────
    private fun ext(path: String): String = path.substringAfterLast('.', "").lowercase()

    internal fun readDocument(path: String): String = when (ext(path)) {
        "docx" -> readDocx(path)
        "xlsx" -> readXlsx(path)
        "pptx" -> readPptx(path)
        else -> throw IllegalArgumentException("不支持的格式 '${ext(path)}' — 仅 docx/xlsx/pptx")
    }

    private fun readDocx(path: String): String = buildString {
        java.io.FileInputStream(path).use { fis ->
            XWPFDocument(fis).use { doc ->
                doc.paragraphs.forEach { appendLine(it.text) }
                doc.tables.forEach { table ->
                    table.rows.forEach { row ->
                        appendLine(row.tableCells.joinToString(" | ") { it.text })
                    }
                }
            }
        }
        trim()
    }.take(MAX_OUTPUT)

    private val formatter = DataFormatter()

    private fun readXlsx(path: String): String = buildString {
        XSSFWorkbook(File(path)).use { wb ->
            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i)
                appendLine("== ${sheet.sheetName} ==")
                sheet.forEach { row ->
                    val cells = (0 until row.lastCellNum).map { c -> formatter.formatCellValue(row.getCell(c)) }
                    appendLine(cells.joinToString("\t"))
                }
            }
        }
    }.trim().take(MAX_OUTPUT)

    private fun readPptx(path: String): String = buildString {
        java.io.FileInputStream(path).use { fis ->
            XMLSlideShow(fis).use { ppt ->
                ppt.slides.forEachIndexed { idx, slide ->
                    appendLine("== Slide ${idx + 1} ==")
                    slide.shapes.forEach { shape ->
                        if (shape is XSLFTextShape) appendLine(shape.text)
                    }
                }
            }
        }
    }.trim().take(MAX_OUTPUT)

    internal fun createDocument(path: String, type: String): String = when (type) {
        "docx" -> {
            XWPFDocument().use { doc -> writeOut(doc, path) }
            "已创建 Word 文档: $path"
        }
        "xlsx" -> {
            XSSFWorkbook().use { wb -> wb.createSheet("Sheet1"); writeOut(wb, path) }
            "已创建 Excel 工作簿: $path (Sheet1)"
        }
        "pptx" -> {
            XMLSlideShow().use { ppt -> writeOut(ppt, path) }
            "已创建 PPT 演示文稿: $path"
        }
        else -> throw IllegalArgumentException("不支持的格式 '$type' — 仅 docx/xlsx/pptx")
    }

    internal fun writeDocument(path: String, content: String): String = when (ext(path)) {
        "docx" -> {
            val existing = File(path).exists()
            val doc = if (existing) {
                java.io.FileInputStream(path).use { XWPFDocument(it) }
            } else {
                XWPFDocument()
            }
            doc.use {
                it.createParagraph().createRun().setText(content)
                writeOut(it, path)
            }
            if (existing) "已追加段落到 Word 文档: $path" else "已创建并写入 Word 文档: $path"
        }
        "xlsx" -> writeXlsxCell(path, content)
        "pptx" -> throw IllegalArgumentException("pptx 排版编辑暂不支持 (Android 无 java.awt), 可先用 office.create 新建空演示文稿")
        else -> throw IllegalArgumentException("不支持的格式 '${ext(path)}' — 仅 docx/xlsx/pptx")
    }

    private fun writeXlsxCell(path: String, content: String): String {
        // 语法: <sheet>:<cellRef>:<value>, 如 Sheet1:A1:Hello
        val parts = content.split(":", limit = 3)
        if (parts.size < 3) {
            throw IllegalArgumentException("xlsx 写入语法: office write <file> <sheet>:<cellRef>:<value>, 如 Sheet1:A1:Hello")
        }
        val (sheetName, ref, value) = Triple(parts[0], parts[1], parts[2])
        val (row, col) = parseCellRef(ref)
        XSSFWorkbook(File(path)).use { wb ->
            val sheet = wb.getSheet(sheetName) ?: wb.createSheet(sheetName)
            val cell = sheet.getRow(row)?.getCell(col) ?: sheet.createRow(row).createCell(col)
            val num = value.toDoubleOrNull()
            if (num != null) cell.setCellValue(num) else cell.setCellValue(value)
            writeOut(wb, path)
        }
        return "已写入 $sheetName!$ref = '$value'"
    }

    /** A1 → (row=0, col=0); 字母为列, 数字为行 (1 起). */
    internal fun parseCellRef(ref: String): Pair<Int, Int> {
        val colPart = ref.takeWhile { it.isLetter() }
        val rowPart = ref.dropWhile { it.isLetter() }
        if (colPart.isEmpty() || rowPart.toIntOrNull() == null) {
            throw IllegalArgumentException("非法单元格引用 '$ref' — 应为 A1 形式")
        }
        val col = colPart.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
        return (rowPart.toInt() - 1) to col
    }

    private fun writeOut(doc: java.io.Closeable, path: String) {
        // 先写内存再落盘, 避免 POI 对象与 FileOutputStream 生命周期耦合导致写入不完整
        val bytes = java.io.ByteArrayOutputStream().use { bos ->
            when (doc) {
                is XWPFDocument -> doc.write(bos)
                is XSSFWorkbook -> doc.write(bos)
                is XMLSlideShow -> doc.write(bos)
                else -> throw IllegalArgumentException("不支持的类型")
            }
            bos.toByteArray()
        }
        java.io.FileOutputStream(path).use { it.write(bytes) }
    }

    companion object {
        private const val MAX_OUTPUT = 8000
    }
}
