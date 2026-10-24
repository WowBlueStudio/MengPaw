// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件元数据守卫 — `self.search` 的关键词表必须与命令注册表同步。
 *
 * 背景: 没有 `commandKeywords` 时 BM25 只按"命名空间+命令名"命中,
 * Agent 用「记忆同步」「设备列表」这类自然语言搜不到 `twin.sync` / `twin.peers`。
 * 这张表一旦写错键名(比如 `peerinfo` 而不是 `peer.info`)会静默失效, 所以锁住。
 */
class MemoryTwinMetadataTest {

    private val metadata = MemoryTwinPlugin().metadata

    /** 注册表里的短名 (去掉 twin. 前缀) — keywords 的 key 必须落在这个集合里。 */
    private val commandShortNames: Set<String> =
        metadata.commands.map { it.removePrefix("twin.") }.toSet()

    @Test
    fun `关键词表的键必须对应已注册命令_没有孤儿键`() {
        val orphans = metadata.commandKeywords.keys.filterNot { it in commandShortNames }
        assertTrue("关键词表存在未注册命令的键 (写错就静默失效): $orphans", orphans.isEmpty())
    }

    @Test
    fun `每条关键词至少有一个中文同义词`() {
        val empty = metadata.commandKeywords.filterValues { it.zh.isEmpty() }.keys
        assertTrue("这些命令只有英文关键词, 中文检索会搜不到: $empty", empty.isEmpty())
    }

    @Test
    fun `核心命令都配了关键词`() {
        // 这几个是 Agent/用户最常自然语言描述的, 缺了就等于搜不到
        val required = setOf("sync", "peers", "status", "pair", "route", "model")
        val missing = required.filterNot { it in metadata.commandKeywords }
        assertTrue("核心命令缺关键词: $missing", missing.isEmpty())
    }

    @Test
    fun `关键词不含空白项`() {
        val blank = metadata.commandKeywords.flatMap { (k, v) -> (v.zh + v.en).map { k to it } }
            .filter { it.second.isBlank() }
        assertFalse("关键词含空白项: $blank", blank.isNotEmpty())
    }
}
