// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.ports

/**
 * 端口单一事实源 — 全项目端口定义集中于此, 禁止在其他文件中散落魔法数字.
 *
 * 三组端口:
 * - INBOUND: App 自身监听 (目前仅 ACP 9876)
 * - OUTBOUND: App 作为客户端连接的外部服务默认端口 (可在 UI 预设/命令参数中修改)
 * - 插件声明端口: 插件通过 PluginMetadata.ports 声明, 由 PluginManager 冲突检测
 *
 * 消费方: self.ports 命令 / 系统提示词注入 / CLI.md 生成 / 各模块端口引用.
 */
object Ports {
    // ── 常量 (代码引用用, 保持编译期常量) ─────────────────────────

    /** ACP (Agent Communication Protocol) — 本机监听, 设备间 Agent 通信. */
    const val ACP = 9876

    /** MP 浏览器 MCP 桥 — 本机监听 (127.0.0.1)。**已退役 (v0.9.0)**: 浏览器控制统一 am 桥单通道. */
    const val BROWSER_MCP = 9880

    /** 本机 MCP 网关 — 标准 MCP JSON-RPC 端点 (127.0.0.1), 任何 MCP client 直连. */
    const val MCP_LOCAL = 9881

    /** 自建 LLM / QwenPaw MCP — 出站 OpenAI 兼容端点默认端口. */
    const val LLM_SELF = 9877

    /** 办公套件 MCP (word/excel/ppt) — 出站默认端口. */
    const val OFFICE_MCP = 9878

    /** ComfyUI API — 出站默认端口. */
    const val COMFYUI = 8188

    /** OpenClaw/Qclaw WebSocket — 外部框架默认端口. */
    const val OPENCLAW_WS = 18789

    /** QwenPaw/Coze REST — 外部框架默认端口 (qwenpaw app 官方默认 8088). */
    const val QWENPAW_REST = 8088

    /** collab-cli UDP 广播 — 外部工具发现默认端口. */
    const val COLLAB_UDP = 9528

    // ── 元数据 ─────────────────────────────────────────────────────

    enum class Direction { INBOUND, OUTBOUND }

    data class PortInfo(
        val port: Int,
        val protocol: String,
        val direction: Direction,
        val owner: String,
        val purpose: String,
        /** 是否可在 UI 预设/命令参数中修改 (INBOUND 恒为 false). */
        val configurable: Boolean = false,
        /** 配置途径说明 (仅 configurable 时有效). */
        val configVia: String = ""
    )

    /** 全部端口 (含元数据) — 唯一结构化端口表. */
    val ALL: List<PortInfo> = listOf(
        PortInfo(
            port = ACP, protocol = "HTTP/JSON", direction = Direction.INBOUND,
            owner = "内核 ACP", purpose = "设备间 Agent 通信 (委派/共享记忆/技能/会话同步/孪生账本/MCP 桥)",
            configurable = false
        ),
        PortInfo(
            port = BROWSER_MCP, protocol = "HTTP/JSON", direction = Direction.INBOUND,
            owner = "MP 浏览器", purpose = "设备内 MCP 桥 — 已退役 (v0.9.0, 浏览器控制统一 am 桥单通道)",
            configurable = false
        ),
        PortInfo(
            port = MCP_LOCAL, protocol = "MCP JSON-RPC", direction = Direction.INBOUND,
            owner = "内核 MCP 网关", purpose = "本机标准 MCP 端点 — 任何 MCP client 直连调用 MengPaw 工具",
            configurable = false
        ),
        PortInfo(
            port = LLM_SELF, protocol = "OpenAI 兼容 HTTP", direction = Direction.OUTBOUND,
            owner = "自建 LLM", purpose = "自建/本地 LLM 与 QwenPaw MCP 端点",
            configurable = true, configVia = "设置页 LLM 地址 / self.mcp connect"
        ),
        PortInfo(
            port = OFFICE_MCP, protocol = "MCP HTTP", direction = Direction.OUTBOUND,
            owner = "办公套件", purpose = "word/excel/ppt MCP 端点",
            configurable = true, configVia = "self.mcp connect office"
        ),
        PortInfo(
            port = COMFYUI, protocol = "HTTP", direction = Direction.OUTBOUND,
            owner = "ComfyUI", purpose = "ComfyUI 图像生成 API",
            configurable = true, configVia = "comfy.run api-url="
        ),
        PortInfo(
            port = OPENCLAW_WS, protocol = "WebSocket", direction = Direction.OUTBOUND,
            owner = "OpenClaw/Qclaw", purpose = "外部框架 WS 协议默认端口",
            configurable = true, configVia = "framework.add 自定义端口"
        ),
        PortInfo(
            port = QWENPAW_REST, protocol = "HTTP", direction = Direction.OUTBOUND,
            owner = "QwenPaw/Coze", purpose = "外部框架 REST 协议默认端口",
            configurable = true, configVia = "framework.add 自定义端口"
        ),
        PortInfo(
            port = COLLAB_UDP, protocol = "UDP 广播", direction = Direction.OUTBOUND,
            owner = "collab-cli", purpose = "外部工具 FILE 协议设备发现",
            configurable = true, configVia = "framework.add 自定义端口"
        ),
    )

    /** 生成 Markdown 端口表 (self.ports / 系统提示词 / CLI.md 共用). */
    fun describe(lang: String = "zh"): String = buildString {
        if (lang == "en") {
            appendLine("## Network Ports")
            appendLine()
            appendLine("### Locally listened (inbound)")
            appendLine("| Port | Protocol | Owner | Purpose |")
            appendLine("|------|----------|-------|---------|")
            ALL.filter { it.direction == Direction.INBOUND }.forEach {
                appendLine("| ${it.port} | ${it.protocol} | ${it.owner} | ${it.purpose} |")
            }
            appendLine()
            appendLine("### External service default ports (outbound, configurable)")
            appendLine("| Port | Protocol | Service | Purpose | Configurable via |")
            appendLine("|------|----------|---------|---------|------------------|")
            ALL.filter { it.direction == Direction.OUTBOUND }.forEach {
                appendLine("| ${it.port} | ${it.protocol} | ${it.owner} | ${it.purpose} | ${it.configVia} |")
            }
            appendLine()
            appendLine("Query runtime status: self.ports. Query plugin-declared ports: plugin.list --ports.")
        } else {
            appendLine("## 网络端口")
            appendLine()
            appendLine("### 本机监听 (inbound)")
            appendLine("| 端口 | 协议 | 归属 | 用途 |")
            appendLine("|------|------|------|------|")
            ALL.filter { it.direction == Direction.INBOUND }.forEach {
                appendLine("| ${it.port} | ${it.protocol} | ${it.owner} | ${it.purpose} |")
            }
            appendLine()
            appendLine("### 外部服务默认端口 (outbound, 可配置)")
            appendLine("| 端口 | 协议 | 服务 | 用途 | 配置途径 |")
            appendLine("|------|------|------|------|----------|")
            ALL.filter { it.direction == Direction.OUTBOUND }.forEach {
                appendLine("| ${it.port} | ${it.protocol} | ${it.owner} | ${it.purpose} | ${it.configVia} |")
            }
            appendLine()
            appendLine("运行时状态查询: self.ports; 插件声明端口: plugin.list --ports.")
        }
    }
}
