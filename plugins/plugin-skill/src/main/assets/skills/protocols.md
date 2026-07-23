---
name: protocols
description: 框架通讯录协议参考：ACP/MCP/WS/REST/FILE 的能力边界与通信方式
enabled: true
category: system
---
# 框架通讯录协议参考

Agent 在与其他框架通信前，必须先理解目标框架的协议能力边界。不同协议的通信方向、发现机制、时效性完全不同。

## 协议一览

| 协议 | 通信方向 | 时效 | 发现机制 | 代表框架 |
|------|---------|------|---------|---------|
| **ACP** | 双向实时 | 毫秒 | mDNS 自动 | MengPaw |
| **MCP** | 单向实时 | 秒 | 手动配置 | Claude Code, Trea, Cursor, OpenCode, Reasonix, Workbuddy |
| **WS** | 单向实时 | 秒 | 手动配置 | OpenClaw, Qclaw, Hermes, Codex |
| **REST** | 单向轮询 | 秒~分 | 手动配置 | QwenPaw, Coze |
| **FILE** | 双向 | 秒 | UDP 广播 | collab-cli |
| **?** | — | — | 待验证 | Kimi Desktop |

---

## ACP — Agent Communication Protocol

**MengPaw 自有协议，仅 MengPaw 使用。**

```
MengPaw A ←── HTTP :9876 ──→ MengPaw B
  DELEGATE →                   执行任务
  ← RESULT                     返回结果
  ← DELEGATE                   对方也能委派
  → RESULT                     返回结果
```

- **通信方向**: 双向 — 双方平等，都可以主动发起任务委派
- **时效**: 实时 — 任务完成后立即返回结果
- **发现**: mDNS `_mengpaw._tcp` → 同一 WiFi 下自动发现
- **加密**: AES-256-CBC 端到端加密
- **信任**: 配对后无限制；未配对访客受限命令集
- **适用场景**: MengPaw 设备之间的对等协作

---

## MCP — Model Context Protocol

**行业标准协议，覆盖最多框架。JSON-RPC over stdio/HTTP/WebSocket。**

```
MengPaw (Client) ── JSON-RPC ──→ MCP Server
  tools/call "审查代码" →        执行
  ← { result: "..." }             返回
  tools/call "展开第2点" →       追问
  ← { result: "..." }             返回
```

- **通信方向**: 单向 — 只有 Client 能发起调用，Server 只能响应
- **时效**: 实时 — 同步 request-response
- **发现**: ❌ 无自动发现，必须手动配置连接地址
- **典型用法**: 
  - Claude Code: `claude --mcp-server`
  - Cursor: `.cursor/mcp.json`
  - OpenCode: MCP 插件配置
- **能力边界**:
  - ✅ MengPaw → 外部 Agent 发任务
  - ✅ 获取返回结果、追问细节
  - ❌ 外部 Agent 不能主动联系 MengPaw
  - ❌ 双方不能同时进行多个并行任务
- **实现**: MengPaw 已有 `mengpaw-kernel/mcp/McpClient.kt`

---

## WS — WebSocket / Unix Socket RPC

**OpenClaw 生态及衍生框架使用的协议。**

```
MengPaw ── WebSocket ──→ OpenClaw / Qclaw / Codex
  { type: "task", ... } →      发送任务
  ← { type: "result", ... }    返回结果
```

- **通信方向**: 单向 — Client → Server 的长连接
- **时效**: 实时 — 长连接推送
- **发现**: ❌ 无自动发现
- **默认端口**: OpenClaw/Qclaw `18789`, Codex 动态 Unix Socket
- **代表框架**:
  - OpenClaw: Node.js Gateway WebSocket
  - Qclaw: OpenClaw 衍生，同协议
  - Hermes: Python Gateway WebSocket + A2A 协议规划中
  - Codex: Rust CLI，Unix Socket 本地通信
- **能力边界**: 同 MCP（单向实时），但支持长连接和流式推送

---

## REST — HTTP API

**最传统但最低效的协议。对方是一个无状态 Web 服务。**

```
MengPaw ── HTTP ──→ QwenPaw / Coze
  POST /api/task →             发任务
  ← { task_id: "abc" }         拿到 ID（不是结果）
  
  GET /api/task/abc →          轮询
  ← { status: "running" }      还没好
  
  GET /api/task/abc →          再轮询
  ← { status: "done", ... }    拿到结果
```

- **通信方向**: 单向 — MengPaw 主动发 HTTP 请求
- **时效**: 轮询 — 每隔 N 秒查询一次，对方不会主动通知
- **发现**: ❌ 无自动发现
- **代表框架**:
  - QwenPaw: FastAPI HTTP，AgentScope 2.0 后端
  - Coze: 字节跳动云端 API `api.coze.com`
- **能力边界**:
  - ✅ 发任务、轮询拿结果
  - ❌ 对方不能主动推送
  - ❌ 异步长任务需要反复轮询，效率低
  - ❌ 每个框架 API 格式不同，需逐个适配

---

## FILE — 文件系统共享协议

**collab-cli 的协议。最轻量，零运行时依赖。**

```
MengPaw ── 读写共享目录 ──→ 其他 Agent

  写 TASK-001.md →           发布任务
  ← 读 TASK-001.md            对方感知
  
  写 REPORT-001.md ←          对方返回结果
  → 读 REPORT-001.md          MengPaw 获取结果
```

- **通信方向**: 双向 — 双方都能读写共享目录
- **时效**: 秒级 — 依赖文件轮询 / inotify
- **发现**: UDP 广播 (端口 9528) + HTTP 同步
- **代表框架**: collab-cli (MIT 开源)
- **三层记忆**: L0 SHARD.md (≤80行) → L1 memory/ (≤50行/文件) → L2 archive/
- **安全**: 角色徽章 (Observer→Chief Engineer)，P0 命令需人工确认
- **优势**:
  - ✅ 纯文件系统，零依赖，兼容任何能读文件的 Agent
  - ✅ 双向通信，支持任务委派和结果返回
  - ✅ 跨设备 UDP 广播自动发现
  - ✅ MIT 开源，可直接兼容
- **劣势**:
  - ❌ 轮询延迟（秒级 vs 毫秒级）
  - ❌ 依赖共享文件系统（同机或网络挂载）

---

## Agent 决策指南

当 Agent 需要与其他框架通信时：

1. **先查协议**: `agent.memory read protocols` 或 `skill.run protocols`
2. **确认对方类型**: 侧边栏通讯录中查看协议标签（ACP/MCP/WS/REST/FILE/?）
3. **匹配能力**:
   - 需要双向对话 → ACP / FILE
   - 单向发任务等结果 → MCP / WS
   - 对方是 REST → 做好轮询准备
   - 协议未知 (?) → 先用 `sys.permission.check` 类命令探测对方端口
4. **配置连接**: 通过 `framework.info <指纹>` 查看地址和端口

---

> 协议详情基于 2026-07 GitHub 源码分析。新框架加入或协议变更时更新本文。
