// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.namespace.NotifyBus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** 最小复现: UNDISPATCHED 订阅 SharedFlow(replay=0) 后同线程立即 tryEmit 是否收到。 */
class NotifyBusTimingTest {

    @Test
    fun `UNDISPATCHED 订阅后立即 banner 应收到`() = runBlocking {
        val banners = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            NotifyBus.events.collect { banners.add(it.text) }
        }
        // 订阅注册同步完成 (count=1 立即可见), 但接收协程的 resume 排队 —
        // 发射后必须让出事件循环 (yield) 才能真正收到
        NotifyBus.banner("测试横幅", NotifyBus.NotifyLevel.WARN)
        kotlinx.coroutines.yield()
        job.cancel()
        assertTrue("最小时序: $banners", banners.contains("测试横幅"))
    }

    @Test
    fun `等待 subscriptionCount 后立即 banner 应收到`() = runBlocking {
        val banners = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            NotifyBus.events.collect { banners.add(it.text) }
        }
        // 等待订阅注册完成 (replay=0: 未注册即发射会丢)
        NotifyBus.subscriptionCount.first { it > 0 }
        NotifyBus.banner("等注册横幅", NotifyBus.NotifyLevel.WARN)
        kotlinx.coroutines.yield()
        job.cancel()
        assertTrue("等注册时序: $banners", banners.contains("等注册横幅"))
    }

    @Test
    fun `异步发射也能收到`() = runBlocking {
        val banners = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            NotifyBus.events.collect { banners.add(it.text) }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            NotifyBus.banner("异步横幅", NotifyBus.NotifyLevel.WARN)
        }
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertTrue("异步时序: $banners", banners.contains("异步横幅"))
    }
}
