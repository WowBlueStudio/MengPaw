// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.extension

import org.junit.Assert.*
import org.junit.Test

class ManifestParserTest {

    private val parser = ManifestParser()

    @Test
    fun `parse valid manifest`() {
        val json = """
            {
                "name": "mengpaw-browser",
                "version": "1.2.0",
                "minCoreVersion": "0.1.0",
                "maxCoreVersion": "0.3.0",
                "apiVersion": 2,
                "packageName": "com.mengpaw.browser"
            }
        """.trimIndent()

        val info = parser.parse(json)
        assertEquals("mengpaw-browser", info.name)
        assertEquals("1.2.0", info.version)
        assertEquals("0.1.0", info.minCoreVersion)
        assertEquals("0.3.0", info.maxCoreVersion)
        assertEquals(2, info.apiVersion)
    }

    @Test
    fun `parse minimal manifest defaults`() {
        val json = """{"name":"test","version":"0.1"}""".trimIndent()
        val info = parser.parse(json)
        assertEquals("test", info.name)
        assertEquals("0.1", info.version)
        assertEquals("0.0.0", info.minCoreVersion)
        assertEquals("99.99.99", info.maxCoreVersion)
        assertEquals(1, info.apiVersion)
    }

    @Test
    fun `parse invalid json returns defaults`() {
        val info = parser.parse("not json")
        assertEquals("unknown", info.name)
    }
}

class VersionTest {

    @Test
    fun `parse valid version`() {
        val v = Version.parse("1.2.3")
        assertEquals(Version(1, 2, 3), v)
    }

    @Test
    fun `parse partial version defaults to 0`() {
        val v = Version.parse("1")
        assertEquals(Version(1, 0, 0), v)
    }

    @Test
    fun `compare versions`() {
        assertTrue(Version(1, 0, 0) < Version(2, 0, 0))
        assertTrue(Version(1, 2, 0) < Version(1, 3, 0))
        assertTrue(Version(1, 2, 3) < Version(1, 2, 4))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
    }

    @Test
    fun `version range check`() {
        val current = Version.parse("0.1.0")
        assertTrue(current >= Version.parse("0.1.0"))
        assertTrue(current <= Version.parse("0.3.0"))
        assertFalse(current >= Version.parse("0.2.0"))
    }
}
