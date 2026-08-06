// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMemoryPreferences 语义测试 (P2 修复: put null 即 remove)。
 *
 * 说明: Vault 本体依赖 EncryptedSharedPreferences/Keystore (Android 框架) 不可在 JVM 单测,
 * 但 Keystore 不可用时的兜底实现 InMemoryPreferences 是纯内存 map 逻辑, 可独立构造测试。
 * 其实现了 android.content.SharedPreferences 接口 — 单测仅调用其自身覆写 (纯 Kotlin map 操作),
 * 不触达 Android 框架方法 (mockable android.jar 的 "not mocked" 异常路径不会命中)。
 */
class InMemoryPreferencesTest {

    /** 每次测试独立的实例, 互不污染。 */
    private fun prefs() = InMemoryPreferences()

    @Test
    fun set后contains为true且getString读回() {
        val p = prefs()
        p.edit().putString("k1", "v1").apply()
        assertTrue(p.contains("k1"))
        assertEquals("v1", p.getString("k1", null))
    }

    @Test
    fun putNull即删除_contains变false_get返回默认值() {
        val p = prefs()
        p.edit().putString("k1", "v1").apply()
        // P2 修复核心: put null 必须等价于 remove — contains 不得再为 true
        p.edit().putString("k1", null).apply()
        assertFalse("put null 后 contains 必须为 false", p.contains("k1"))
        assertNull("put null 后 get 返回默认值", p.getString("k1", null))
        assertEquals("put null 后 get 返回显式默认值", "fallback", p.getString("k1", "fallback"))
    }

    @Test
    fun remove后contains为false() {
        val p = prefs()
        p.edit().putString("k1", "v1").apply()
        p.edit().remove("k1").apply()
        assertFalse(p.contains("k1"))
        assertNull(p.getString("k1", null))
    }

    @Test
    fun 多键互不干扰() {
        val p = prefs()
        p.edit().putString("a", "1").putString("b", "2").apply()
        assertEquals("1", p.getString("a", null))
        assertEquals("2", p.getString("b", null))
        p.edit().remove("a").apply()
        assertFalse(p.contains("a"))
        assertTrue("删除 a 不影响 b", p.contains("b"))
        assertEquals("2", p.getString("b", null))
    }

    @Test
    fun 一个editor多次put后一次apply全部生效() {
        val p = prefs()
        val e = p.edit()
        e.putString("x", "1")
        e.putString("y", "2")
        e.apply()
        assertTrue(p.contains("x"))
        assertTrue(p.contains("y"))
    }

    @Test
    fun commit与apply等价() {
        val p = prefs()
        assertTrue(p.edit().putString("k", "v").commit())
        assertTrue(p.contains("k"))
        assertEquals("v", p.getString("k", null))
    }

    @Test
    fun 各类型存取往返() {
        val p = prefs()
        p.edit()
            .putInt("i", 42)
            .putLong("l", 9_000_000_000L)
            .putFloat("f", 3.14f)
            .putBoolean("b", true)
            .putStringSet("s", mutableSetOf("a", "b"))
            .apply()
        assertEquals(42, p.getInt("i", 0))
        assertEquals(9_000_000_000L, p.getLong("l", 0L))
        assertEquals(3.14f, p.getFloat("f", 0f), 0.0001f)
        assertTrue(p.getBoolean("b", false))
        assertEquals(mutableSetOf("a", "b"), p.getStringSet("s", null))
    }

    @Test
    fun 类型不匹配时按类型返回默认值() {
        val p = prefs()
        p.edit().putInt("k", 5).apply()
        assertEquals("getString 遇 Int 值返回默认", "d", p.getString("k", "d"))
        assertEquals("getBoolean 遇 Int 值返回默认", true, p.getBoolean("k", true))
        assertEquals("getInt 正常读回", 5, p.getInt("k", 0))
    }

    @Test
    fun clear清空全部() {
        val p = prefs()
        p.edit().putString("a", "1").putString("b", "2").apply()
        p.edit().clear().apply()
        assertFalse(p.contains("a"))
        assertFalse(p.contains("b"))
        assertTrue(p.getAll().isEmpty())
    }

    @Test
    fun clear后put新键仅剩新键() {
        // Android Editor 语义: clear() 标记全量删除, 之后 put 的键保留, 旧键全部移除
        val p = prefs()
        p.edit().putString("old", "1").apply()
        p.edit().clear().putString("new", "2").apply()
        assertFalse("旧键必须被清除", p.contains("old"))
        assertTrue("新键保留", p.contains("new"))
        assertEquals(1, p.getAll().size)
    }

    @Test
    fun getAll返回副本不影响内部存储() {
        val p = prefs()
        p.edit().putString("a", "1").apply()
        @Suppress("UNCHECKED_CAST")
        val snapshot = p.getAll() as MutableMap<String, Any?>
        snapshot["a"] = "tampered"
        snapshot["injected"] = "x"
        assertEquals("外部修改副本不影响内部", "1", p.getString("a", null))
        assertFalse("外部注入键不生效", p.contains("injected"))
        assertEquals(1, p.getAll().size)
    }
}
