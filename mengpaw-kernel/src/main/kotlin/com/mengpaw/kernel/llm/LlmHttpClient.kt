// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * 模块级共享 LLM HTTP 客户端 (v0.29.2, Reasonix 对照 #2 — HTTP 传输层)。
 *
 * 此前每个 AdaptiveLlmProvider / RemoteApi 各自 new HttpClient(OkHttp) —
 * 会话/角色切换即重建连接池, 每次切换重新 TCP+TLS 握手 (~2-4 RTT)。
 * 共享单例让所有 provider 复用同一连接池 (HTTP/2 ALPN 协商 + keep-alive),
 * 会话切换不再握手。池规格: 8 空闲连接 / 5 分钟保活 (OkHttp 默认 5/5min,
 * 显式放大并声明意图, 对标 Reasonix netclient.go NewTransport 克隆池)。
 *
 * 超时语义 (对齐 Ktor 3.x OkHttp 引擎字节码实证, 见 lessons.md §5.5):
 *   - connect 20s   (DNS+TCP+TLS)
 *   - read 120s     (唯一活超时 — 静默判定阈值, 对齐 Reasonix idle watchdog 120s;
 *                    推理思考期 60s+ 无数据, 120s 仍留 ~60s 思考余量, v0.29.2)
 *   - 无 callTimeout — requestTimeoutMillis 不被 OkHttp 引擎映射 (死配置),
 *     且 callTimeout 会误杀生成期 >120s 的流式响应
 *   - retryOnConnectionFailure(true) — 连接级失败由 OkHttp 立即重试,
 *     与上层 executeWithRetry 指数退避互补
 *   - pingInterval(60s) — HTTP/2 主动探活 (v0.29.2): 半死连接 60s 内被发现,
 *     不等静默超时; HTTP/1.1 连接无副作用 (Reasonix 120s idle watchdog 对标)
 *
 * 单例不 close — 进程生命周期共享, provider.close() 为 no-op (见两处实现)。
 */
internal object LlmHttpClient {
    val ktor: HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(20, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                retryOnConnectionFailure(true)
                pingInterval(60, TimeUnit.SECONDS)
            }
        }
    }
}
