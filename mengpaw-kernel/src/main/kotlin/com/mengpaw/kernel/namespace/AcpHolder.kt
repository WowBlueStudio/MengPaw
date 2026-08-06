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
}
