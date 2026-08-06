// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.session.AttachmentData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * AttachmentPayload 二进制挂载 + 指纹缓存回归测试 (v0.32.1+ 重发成本修复)。
 *
 * 覆盖:
 * - 图片/音频挂载 `_image`/`_audio_data` 键, 超限跳过 (稀疏文件, 不占真实磁盘)
 * - 指纹缓存: 同文件 (path|size|mtime 不变) 重复调用不重读盘 — 文件删除后
 *   仍返回缓存值即证明未重新 readBytes; 内容变化 (size 变) 重新编码
 */
class AttachmentPayloadTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun imageAtt(path: String, mime: String = "image/png") = AttachmentData(
        type = "image", path = path, mimeType = mime, name = File(path).name
    )

    private fun audioAtt(path: String, format: String = "m4a") = AttachmentData(
        type = "audio", path = path, format = format
    )

    @Test
    fun `image attachment mounts _image data uri`() {
        val file = tmp.newFile("a.png")
        file.writeBytes(ByteArray(64) { it.toByte() })
        val result = AttachmentPayload.attachBinary(
            mapOf("role" to "user", "content" to "见图"),
            listOf(imageAtt(file.absolutePath))
        )
        assertTrue("应挂 _image 键", result.containsKey("_image"))
        assertTrue("data URI 前缀", result["_image"]!!.startsWith("data:image/png;base64,"))
        assertEquals("role/content 保留", "user", result["role"])
    }

    @Test
    fun `audio attachment mounts _audio_data with format`() {
        val file = tmp.newFile("a.m4a")
        file.writeBytes(ByteArray(32) { 1 })
        val result = AttachmentPayload.attachBinary(
            mapOf("role" to "user", "content" to "听"),
            listOf(audioAtt(file.absolutePath))
        )
        assertTrue(result.containsKey("_audio_data"))
        assertEquals("m4a", result["_audio_format"])
    }

    @Test
    fun `oversized image is skipped`() {
        // 稀疏文件: length() 报 9MB 但实际不占磁盘 — 验证 IMAGE_BINARY_MAX 上限跳过
        val file = tmp.newFile("big.png")
        RandomAccessFile(file, "rw").use { it.setLength(9L * 1024 * 1024) }
        val result = AttachmentPayload.attachBinary(
            mapOf("role" to "user", "content" to "大图"),
            listOf(imageAtt(file.absolutePath))
        )
        assertFalse("超限图片不挂二进制键", result.containsKey("_image"))
    }

    @Test
    fun `missing file is skipped`() {
        val result = AttachmentPayload.attachBinary(
            mapOf("role" to "user", "content" to "x"),
            listOf(imageAtt(tmp.root.absolutePath + "/nonexistent.png"))
        )
        assertFalse(result.containsKey("_image"))
    }

    @Test
    fun `repeated calls return identical base64 for unchanged file`() {
        // 同文件 (size/mtime 不变) 多 step 重复挂载: 值必须稳定一致 (缓存路径行为)
        val file = tmp.newFile("stable.png")
        file.writeBytes(ByteArray(128) { 7 })
        val att = imageAtt(file.absolutePath)

        val first = AttachmentPayload.attachBinary(mapOf("role" to "user", "content" to "a"), listOf(att))
        val second = AttachmentPayload.attachBinary(mapOf("role" to "user", "content" to "b"), listOf(att))
        assertNotNull(first["_image"])
        assertEquals("未变化文件两次挂载 base64 一致", first["_image"], second["_image"])
    }

    @Test
    fun `deleted file after first encode is skipped`() {
        // 文件删除后不再挂载 (存在性检查优先) — 语义: 附件失效即跳过, 不返回陈旧二进制
        val file = tmp.newFile("gone.png")
        file.writeBytes(ByteArray(64) { 3 })
        val att = imageAtt(file.absolutePath)
        assertTrue(AttachmentPayload.attachBinary(mapOf("role" to "user", "content" to "a"), listOf(att)).containsKey("_image"))
        file.delete()
        assertFalse("文件已删除应跳过挂载", AttachmentPayload.attachBinary(mapOf("role" to "user", "content" to "b"), listOf(att)).containsKey("_image"))
    }

    @Test
    fun `file content change invalidates cache`() {
        val file = tmp.newFile("change.png")
        file.writeBytes(ByteArray(64) { 1 })
        val att = imageAtt(file.absolutePath)

        val first = AttachmentPayload.attachBinary(mapOf("role" to "user", "content" to "a"), listOf(att))
        val b64a = first["_image"]

        // 内容变化 → size 指纹变化 → 重新编码
        file.writeBytes(ByteArray(128) { 2 })
        val second = AttachmentPayload.attachBinary(mapOf("role" to "user", "content" to "b"), listOf(att))
        assertNotEquals("size 变化应重新编码", b64a, second["_image"])
    }
}
