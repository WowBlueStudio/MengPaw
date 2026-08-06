// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RunningStepTracker 冒烟测试 (P2 新增文件级类 — @Volatile index/ref 追踪器)。
 *
 * 说明: 类为 AgentViewModel.kt 的 private 文件级类, 无法直接引用 — 经反射构造并调用
 * getter/setter (类本身纯 Kotlin, 仅引用 ChatMessageUi.AgentStep 纯数据类)。
 *
 * 覆盖: 构造默认值 (index=-1/ref=null)、set 后读回、并发读写不崩且写入可见 (volatile 冒烟)。
 */
class RunningStepTrackerTest {

    /** 定位 private 文件级类 — 兼容 Kotlin 对私有顶层类的两种 JVM 命名。 */
    private fun trackerClass(): Class<*> {
        val candidates = listOf(
            "com.mengpaw.shell.ui.screens.RunningStepTracker",
            "com.mengpaw.shell.ui.screens.AgentViewModelKt\$RunningStepTracker"
        )
        return candidates.mapNotNull { name ->
            try { Class.forName(name) } catch (_: ClassNotFoundException) { null }
        }.firstOrNull() ?: error("找不到 RunningStepTracker 类: $candidates")
    }

    private fun newTracker(): Any {
        return trackerClass().getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }

    // 反射方法句柄缓存 — 并发测试高频调用, 每次 getDeclaredMethod 极慢
    private val setIndexMethod by lazy {
        trackerClass().getDeclaredMethod("setIndex", Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
    }
    private val getIndexMethod by lazy {
        trackerClass().getDeclaredMethod("getIndex").apply { isAccessible = true }
    }
    private val setRefMethod by lazy {
        trackerClass().getDeclaredMethod("setRef", ChatMessageUi.AgentStep::class.java)
            .apply { isAccessible = true }
    }
    private val getRefMethod by lazy {
        trackerClass().getDeclaredMethod("getRef").apply { isAccessible = true }
    }

    private fun setIndex(tracker: Any, value: Int) = setIndexMethod.invoke(tracker, value)

    private fun getIndex(tracker: Any): Int = getIndexMethod.invoke(tracker) as Int

    private fun setRef(tracker: Any, ref: ChatMessageUi.AgentStep?) = setRefMethod.invoke(tracker, ref)

    private fun getRef(tracker: Any): ChatMessageUi.AgentStep? = getRefMethod.invoke(tracker) as ChatMessageUi.AgentStep?

    @Test
    fun 构造默认值() {
        val tracker = newTracker()
        assertEquals("index 默认 -1", -1, getIndex(tracker))
        assertNull("ref 默认 null", getRef(tracker))
    }

    @Test
    fun set后读回() {
        val tracker = newTracker()
        setIndex(tracker, 3)
        assertEquals(3, getIndex(tracker))
        setIndex(tracker, 42)
        assertEquals("重复 set 覆盖旧值", 42, getIndex(tracker))
    }

    @Test
    fun ref存取与置空() {
        val tracker = newTracker()
        val step = ChatMessageUi.AgentStep(
            step = 2, thought = "思考", action = "fs.write", content = "结果", isRunning = true
        )
        setRef(tracker, step)
        assertSame("ref 应原样读回同一实例", step, getRef(tracker))
        setRef(tracker, null)
        assertNull("ref 置空后可读回 null", getRef(tracker))
    }

    @Test
    fun 并发读写不崩且写入可见() {
        val tracker = newTracker()
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(2000) { i ->
                    // 每线程写互不重叠的值域: t*1000 + [0, 1999]
                    setIndex(tracker, t * 1000 + i)
                    // 穿插读 — 验证并发读不抛异常 (volatile 读)
                    getIndex(tracker)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // join 建立 happens-before — 最后写入线程的值必然可见, 不得残留默认 -1。
        // 线程 t 写值域 [t*1000, t*1000+1999] → 并集 0..4999
        val finalValue = getIndex(tracker)
        assertTrue(
            "最终值必须是某线程写过的值 (volatile 可见性), 实际: $finalValue",
            finalValue in 0..4999
        )
    }
}
