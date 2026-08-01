// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 内核级专用调度器 — 隔离不同 I/O 类型, 防止相互阻塞.
 *
 * 全部基于 Dispatchers.IO 的 limitedParallelism 视图, 共享底层线程池但限制并发,
 * 避免不同类型 I/O 互相抢占.
 *
 * | 调度器       | 并发 | 用途                         |
 * |-------------|-----|------------------------------|
 * | PROMPT_IO   | 2   | 提示词文件 I/O, 小文件关键路径   |
 * | LLM_IO      | 4   | LLM HTTP 调用, 长时间等待      |
 * | BACKGROUND  | 8   | 后台批量任务 (备份/清理/同步)    |
 */
object KernelDispatchers {
    /** 提示词/工作区文档文件 I/O: 小文件读取, ReAct 关键路径, 最多 2 并发 */
    val PROMPT_IO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /** LLM 远程 API 调用: 长时间网络等待, 最多 4 并发 */
    val LLM_IO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** 后台批量任务: 日志, 同步, 清理等低优先级操作, 最多 8 并发 */
    val BACKGROUND: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8)
}
