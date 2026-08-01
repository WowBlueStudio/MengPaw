# MengPaw 框架通信协议

> 协议进内核,连接器进插件。非 MengPaw 框架接入 MengPaw 的总指南。
> 最后更新: 2026-08-01 · 对应内核 v0.22.x 协议层

---

## 1. 协议总览

三层结构:

```
mengpaw-kernel (协议核心, 纯 JVM)
  ├─ ACP   — 框架间信令层: 消息语义 (22 种) / 传输 (HTTP :9876) / 信任 (配对+加密) / MCP-over-ACP 桥
  ├─ MCP   — 能力调用层: 标准 JSON-RPC (tools/list·call / resources / prompts)
  └─ SPI   — FrameworkAdapter 接口 + FrameworkAdapterRegistry (连接器注册表)

plugin-framework (内置协议插件, 随 APK)
  ├─ mDNS 发现 / 信任表 / 通讯录 (17 种框架类型)
  ├─ 本机 MCP 网关 127.0.0.1:9881 (任何 MCP client 直连)
  └─ framework.connect/call/disconnect/adapters (连接器分派)

plugin-connector-* (外部分发连接器插件, 市场安装, 不内置)
  ├─ connector-openclaw (WS :18789)   ── 已发布
  ├─ connector-qwenpaw   (REST :8080)  ── 已发布
  └─ 第三方框架: 按第 3 节 SPI 自行实现
```

双轨接入:

| 轨道 | 通道 | 适用 | 安全 |
|---|---|---|---|
| **本机轨** | 标准 MCP over HTTP `127.0.0.1:9881` | 同设备 MCP 客户端 (Claude Code / Claude Desktop / 任意) | 仅回环地址, 不暴露网络 |
| **远程轨** | MCP over ACP `:9876` | 跨设备 MengPaw ↔ MengPaw | 配对 (短码/指纹) + AES-256-CBC |
| **框架轨** | 连接器插件 (WS/REST/...) | 非 MengPaw 框架 (OpenClaw/QwenPaw/...) | 每框架各自连接方式 |

## 2. 本机接入(最简单的路径)

任何标准 MCP 客户端直连 `http://127.0.0.1:9881/mcp`,JSON-RPC 2.0:

```bash
# 健康检查
curl http://127.0.0.1:9881/health
# → {"ok":true,"status":"online"}

# 列出工具
curl -X POST http://127.0.0.1:9881/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
# → {"jsonrpc":"2.0","id":1,"result":[{"name":"fs.stat","description":"...",...},...]}

# 调用工具 (执行 MengPaw 插件命令)
curl -X POST http://127.0.0.1:9881/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"fs.stat","arguments":{"path":"/tmp"}}}'
# → {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"..."}]}}
```

- 工具命名: 插件命令自动映射为 `{ns}.{cmd}`(如 `fs.stat`、`net.curl`、`tavily.search`),另有 provider 工具(如浏览器 `browser_navigate`)
- 网关生命周期: plugin-framework 激活即启动(Shell 内置)
- MCP 客户端配置示例(Claude Code `~/.claude.json` 或项目 `.mcp.json`):
  ```json
  {"mcpServers": {"mengpaw": {"url": "http://127.0.0.1:9881/mcp"}}}
  ```

## 3. 连接器插件开发指南(第三方框架接入主路径)

实现内核 SPI,5 步:

**Step 1 — 建插件模块**(参照 `plugins/plugin-connector-qwenpaw/`):
```kotlin
// build.gradle.kts: 只依赖 kernel + 连接所需依赖
implementation(project(":mengpaw-kernel"))
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
```

**Step 2 — 实现 FrameworkAdapter**(`com.mengpaw.kernel.spi.FrameworkAdapter`):
```kotlin
class MyFrameworkConnectorPlugin : Plugin, FrameworkAdapter {
    override val frameworkName: String = "myframework"   // 与 FrameworkPeerStore.FRAMEWORK_TYPES 键一致
    override suspend fun connect(target: FrameworkTarget): Result<Unit> { /* 建连接 */ }
    override suspend fun disconnect() { /* 断连接 */ }
    override suspend fun callTool(tool: String, args: Map<String, String>): Result<String> {
        /* 把调用翻译成远端框架协议; 返回文本结果 */
    }
    override fun isOnline(): Boolean = /* 连接存活 */
}
```

**Step 3 — onInstall 注册 / onUninstall 注销**:
```kotlin
override suspend fun onInstall(ctx: PluginContext) {
    FrameworkAdapterRegistry.register(this)   // 内核注册表 — 零框架耦合
}
override suspend fun onUninstall() {
    FrameworkAdapterRegistry.unregister(frameworkName)
}
```

**Step 4 — 通讯录登记**: 在 plugin-framework 的 `FrameworkPeerStore.FRAMEWORK_TYPES` + `PROTOCOL_LABELS` 加类型(随内置插件发布,或后续扩展)。

**Step 5 — 构建与发布**:
- 连接器插件**不内置**(settings.gradle.kts 不含 include)— 独立构建 AAR + plugins.json 加 remote 条目
- 安装后自动注册:`framework.adapters` 可见 → `framework.connect <peer>` → `framework.call <peer> <tool> [jsonArgs]`

## 4. 最小 ACP 子集(可选,远程配对路径)

非 MengPaw 框架若需跨设备配对接入,实现 4 条消息即可(HTTP POST `:9876/acp`):

| 消息 | 用途 |
|---|---|
| `DISCOVER` | 发现 + 协议协商: payload `{"protocols":["acp/1.0","acp/1.1","mcp/1.0"]}` |
| `HEARTBEAT` | 存活检测 (ttl=1) |
| `MCP_REQUEST` | 封装 JSON-RPC (tools/list / tools/call), 带 `requestId` |
| `MCP_RESPONSE` | MCP 结果回发 (requestId 关联) |

```json
{"from":"myfw","to":"*","type":"MCP_REQUEST","requestId":"abc-1",
 "payload":"{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}"}
```

信任: 配对(短码/指纹)后为 TRUSTED 全量放行;GUEST 受限(只读命令白名单,写/装/执行类黑名单 — PromptFirewall)。

## 5. 消息格式参考

### AcpMessage (内核 `kernel/acp/AcpProtocol.kt`)

| 字段 | 类型 | 说明 |
|---|---|---|
| from | String | 发送方 agent/框架 ID |
| to | String | 接收方 ("*" = 广播) |
| type | String | 22 种枚举值之一 (DELEGATE/RESULT/WS_MANIFEST/MCP_REQUEST...) |
| payload | String | 类型化载荷 (JSON 字符串约定) |
| ttl | Int | 跳数上限 (默认 10; HEARTBEAT=1) |
| requestId | String | 请求-响应关联 ID (v0.22.1+, 旧消息默认空, 兼容) |

### 传输层 (内核 `kernel/acp/AcpTransport.kt`)

- 端点: `POST http://{address}:{port}/acp`,body = AcpMessage JSON
- 加密: 配对后 `X-MengPaw-Encrypt: AES-256-CBC` + `X-MengPaw-From` 头,payload 加密
- 身份: 远端 socket 地址绑定 + msg.from 校验(防伪造)

## 6. 连接器清单

| 框架 | 类型 | 通道 | 连接器 | 状态 |
|---|---|---|---|---|
| MengPaw | ACP | HTTP :9876 | 内置 (内核) | ✅ |
| MP 浏览器 | MCP | HTTP :9880 | 内置 (plugin-browser-mcp) | ✅ |
| 任意 MCP 客户端 | MCP | HTTP :9881 | 内置 (plugin-framework 网关) | ✅ |
| OpenClaw / Qclaw | WS | :18789 | connector-openclaw | 📦 已发布 |
| QwenPaw / Coze | REST | :8080 | connector-qwenpaw | 📦 已发布 |
| Claude Code / Claude Desktop | MCP | 本机 :9881 | 直连 (零插件) | ✅ |
| Hermes / Trea / Cursor / OpenCode / Codex / Kimi... | ? | 待验证 | 待接入 (按 §3 SPI) | ⏳ |

---

> 端口单一事实源: `kernel/ports/Ports.kt`(self.ports / 系统提示词 / CLI.md 共用)。
> 加 ACP 消息类型 = 内核唯一强制破坏点(`AcpServer.processMessage` 的 when 穷尽性)。
