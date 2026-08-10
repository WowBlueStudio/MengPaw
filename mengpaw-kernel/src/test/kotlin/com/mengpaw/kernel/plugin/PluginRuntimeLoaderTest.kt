// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PluginRuntimeLoader 加载链路单元测试 — dex 容器检查 / META-INF/plugin-class 主类清单 /
 * 标准 AAR 明确报错 (防"假安装")。
 */
class PluginRuntimeLoaderTest {

    private fun createZip(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("plugin-loader-test", ".jar")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `standard aar without classes dex returns actionable error`() {
        val jar = createZip(
            "classes.jar" to "dummy",
            "AndroidManifest.xml" to "<manifest />"
        )
        val entry = MarketplaceEntry(id = "connector-openclaw-plugin", name = "OpenClaw 连接器", version = "0.1.0")
        val result = runBlocking { PluginRuntimeLoader.load(PluginManager(), jar, entry) }
        assertTrue("应返回可操作错误而非静默假安装: $result", result != null)
        assertTrue("错误应说明缺 classes.dex: $result", result!!.contains("classes.dex"))
        assertTrue("错误应给出 dex JAR 修复方向: $result", result.contains("dex JAR"))
    }

    @Test
    fun `dex jar without manifest falls back to candidates on jvm`() {
        val jar = createZip("classes.dex" to "dex")
        val entry = MarketplaceEntry(id = "connector-openclaw-plugin", name = "OpenClaw 连接器", version = "0.1.0")
        // JVM 无 dalvik DexClassLoader → 返回 null (desktop 不支持运行时加载), 但 dex 检查已通过
        assertEquals(null, runBlocking { PluginRuntimeLoader.load(PluginManager(), jar, entry) })
    }

    @Test
    fun `plugin class manifest is read from jar`() {
        val jar = createZip(
            "META-INF/plugin-class" to "com.mengpaw.plugin.connector.openclaw.OpenClawConnectorPlugin"
        )
        assertEquals(
            "com.mengpaw.plugin.connector.openclaw.OpenClawConnectorPlugin",
            PluginRuntimeLoader.readPluginClass(jar)
        )
    }

    @Test
    fun `missing plugin class manifest returns null`() {
        val jar = createZip("classes.dex" to "dex")
        assertEquals(null, PluginRuntimeLoader.readPluginClass(jar))
    }
}
