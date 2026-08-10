// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

/** Shared ACP server instance — accessible from CLI and AgentEngine. */
object AcpHolder {
    // SECURITY: sharedSecret is derived from a baseline key; callers should override
    // via AcpServer(profile, port, derivedSecret) for production use with twin pairing.
    val server = com.mengpaw.kernel.acp.AcpServer(
        com.mengpaw.kernel.agent.AgentProfile(),
        port = com.mengpaw.kernel.ports.Ports.ACP,
        sharedSecret = "acp-default-require-derive-key-for-production"
    )
    var transport: com.mengpaw.kernel.acp.AcpHttpTransport? = null

    init {
        // v0.36 舰队闭环: 结果回传 handler 常驻注册 — 对端 FLEET_RESULT 直达 FleetRuntimeStore
        try { server.registerHandler(com.mengpaw.kernel.acp.FleetResultHandler()) } catch (_: Exception) {}
    }

    /**
     * 确保 ACP 端口在监听 (v0.35.4): 框架配对 handler 注册在 [server] 上,
     * 此前只有手动 `self.acp start` 才创建 transport — 对端收不到配对请求。
     * 幂等: 已有监听则直接复用; 端口被占用时 startListener 内部静默失败。
     */
    fun ensureListening(): com.mengpaw.kernel.acp.AcpHttpTransport {
        transport?.let { if (it.isConnected()) return it }
        val t = com.mengpaw.kernel.acp.AcpHttpTransport(server, com.mengpaw.kernel.ports.Ports.ACP)
        server.registerTransport(t)
        transport = t
        t.startListener()
        return t
    }
}
