# 记忆与设备库存 · Git 化同步设计

> **状态**: 设计稿（未实施，无代码变更） | **版本**: 1.0 | **日期**: 2026-10-22
> **范围**: 记忆孪生（`plugin-memory-twin`）的存储与同步层重构 + 设备/能力库存
> **实施时必同步更新**: 开发指南 §3.5.1（孪生架构）、§5.1（命令表）、§3.7（测试快照）

---

## §0 一页纸结论

四条诉求 → 四个设计承诺：

| 诉求 | 设计承诺 |
|---|---|
| 长期记忆文件，多设备用 Git 对账 | 快照树 + **三方合并**（base = 上次同步头），冲突进文件并交给 Agent 裁决，永不静默 LWW |
| 中期项目记忆，多端 Agent 以 Git 形式提交记账 | **只增账本**：每次写入成条目（含会话/设备/时钟），编辑 = 新条目 + `supersedes`，原始条目不可变 |
| 避免幻觉与归纳错误、减轻上下文压力 | **归纳从写路径移到读路径**：dream 降级为可重建视图，每条结论带 `sources[]`；查询按需切片，不再"读全部分片" |
| 短期对话不涉 Git | `dialog/` 本就在孪生排除名单，**零改动** |
| 设备环境清单，多端共同维护 | `devices/<deviceId>.md` **单写者**（每端只写自己一行），随同一套对象库同步；库存与运行时彻底分离 |

一句话架构：**文件是接口，对象库是事实，索引是视图。** Agent 的读写方式一行不改。

---

## §1 现状与问题（为什么要改）

当前实现（`plugin-memory-twin` 25 文件 / 3,866 行 / 64 用例）五处结构性问题，全部有代码证据：

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| 1 | 哈希比对永不命中 | 发送端 `entry.hash.take(16)`（[TwinSyncProtocol.kt:72](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinSyncProtocol.kt)），接收端拿 16 位比 64 位（[TwinAcpHandler.kt:92](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinAcpHandler.kt)） | 每轮 60s 全量重推整个工作区；每次落盘触发 `notifyDocChanged` → 提示词缓存每分钟失效 |
| 2 | 冲突 = 整文件放弃一侧 | `applyWorkspaceFile` LWW + `.conflict` 备份（[TwinWorkspace.kt:74](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinWorkspace.kt)） | 两侧各改 `memory.md` 就丢一整段记忆 |
| 3 | 删除不传播 | `removeWorkspaceFile()` 无生产调用方（仅测试调用） | 梦境压缩掉的分片在其他设备永久残留，重复压缩、越积越多 |
| 4 | 归纳有损且不可追溯 | `DreamEngine` 读全部分片 → 写 dream → 删分片 | 归纳错一次源材料即消失，无法追查哪段读错 |
| 5 | 能力卡三套并存、不持久 | `CapabilityCard`（内存 peer map）/ `FleetCapability`（内存 cache → Notes）/ `twin.model`（独立展示） | 重启即丢；`twin.route` 只认"本次会话收到过上报"的节点 |

补充事实：`deviceId = SHA-256(Build.FINGERPRINT)[:32]`，**同型号同 ROM 的两台设备 id 相同**，库存、信任、密钥会互相串台。这是做库存的先决修复项。

---

## §2 总体模型

### 2.1 四类数据与写入语义

| 类别 | 文件 | 谁写 | 写入语义 | Git 模型 |
|---|---|---|---|---|
| **L 长驻文档** | `soul.md` `profile.md` `agents.md` `boost.md` `trigger.md` `heartbeat.md` `trumanshow.md` `memory/memory.md` | 人 + Agent，就地改写 | 并发编辑 | 快照树 + **三方合并（diff3）** |
| **M 中期账本** | `memory/memory_{date}.md`、`memory/project_*_memory.md`、`{date}_dream.md` | Agent 追加 | **只增不改** | 快照树 + **追加并集** + 条目账本 |
| **D 设备库存** | `{AGENTS}/twin/devices/<deviceId>.md` | 每端只写自己 | 单写者覆盖 | 快照树（无冲突） |
| **S 短期** | `dialog/` `inbox/` `tool_results/` `memory/backup/` | 系统 | — | **不参与** |

`memory/backup/` 与 `inbox/` 保持排除；`dialog/` 不动 —— 短期不涉 Git 这一条现状已经满足。

### 2.2 三层结构

```
① 接口层   工作区文件（Agent/人直接读写，格式不变）
              │  一次同步 = 对当前文件集做快照
② 事实层   对象库（内容寻址 blob / tree / snapshot）+ 中期账本条目
              │  派生
③ 视图层   索引（devices/index.md、memory/index.md）、dream 归纳、twin.status
```

**不引入 checkout / index**：工作区文件永远是活状态，对象库只是它的追加快照日志。Agent 不需要"记得提交"，也不需要理解 Git。

### 2.3 纯 pull 收敛（去掉 push）

每端只从对端**拉取**，不推送：

```
轮询（60s/300s）：对每个在线 peer 发 SYNC_HELLO
  头相同 → 结束（1 RTT，~200B）
  头不同 → 拉对象 → 三方合并 → 本地提交 → local ref 前进
对方下一轮拉我，自然拿到我的提交 → 2 轮内全网收敛
```

好处：协议少一半、对端无需写权限、鉴权只需"可读"、失败重试天然幂等。

### 2.4 为什么不用 JGit、不做 packfile

| 选择 | 理由 |
|---|---|
| 不用 JGit | JGit 6/7 要求 Java 11（Android 无 `ProcessHandle`/`Files.readString`）；可用的是 5.13 线（Java 8），jgit + JavaEWAH + slf4j ≈ 3MB。为"与真 git 互通"付全价，收益只取 10%；还要背 index/lock/`.git` 污染工作区 |
| 不做 packfile / delta 链 | 200 个 `.md` ≈ 800KB，逐文件 zlib 已 3～4x；跨版本 delta 最多再省一半（≈400KB），代价是 delta 链、base 选择、损坏恢复。**不做**；若将来需要，只做"同路径上一版"单级 delta（约 80 行，对追加型文件命中率极高） |
| 用 SHA-256 不用 SHA-1 | 不追求格式互通；`MessageDigest("SHA-256")` 现成（代码已在用），无 Android 兼容问题 |

---

## §3 对象库

### 3.1 目录

```
{AGENTS}/twin/
├── store/
│   ├── objects/ab/cdef…            ← zlib("blob <len>\0" + bytes)，内容寻址
│   ├── refs/ws-<agent>/heads       ← 多 head（一行一个 sha）
│   ├── refs/ws-<agent>/local       ← 本机上次成功同步头（= 合并 base）
│   ├── refs/dev/heads
│   ├── refs/dev/local
│   └── tmp/
├── ledger/<agent>/entries.jsonl    ← 中期账本（只增，随 ws 命名空间同步）
├── devices/<deviceId>.md           ← 设备库存（随 dev 命名空间同步）
├── devices/index.md                ← 派生视图（本地生成，不参与同步）
├── audit.log                       ← 保留：安全事件（REVOKE/配对）
└── clock                           ← Lamport 时钟（单行整数）
```

`{AGENTS}/twin` 已在 `DataPaths.AGENT_SYSTEM_DIRS` 白名单内（[DataPaths.kt:167](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/DataPaths.kt)），不会被误判为 Agent 工作区。

### 3.2 对象格式

```
blob     = zlib("blob <len>\0" + content)          sha = SHA-256(header + content)
tree     = zlib(JSON: {relPath: blobSha})          ← v1 扁平
snapshot = zlib(JSON: {tree, parents[], author{deviceId,agent}, lamport, wall, msg})
```

**v1 采用扁平 tree（一个 `relPath → blobSha` 的 JSON 映射）**，不做 git 那种层级 tree：

- 收益差：差异轮全量清单 ≈ 200 条 × 80B ≈ 16KB（zlib 后 ~3KB）；层级 tree 可降到 O(变化文件数)。
- 成本差：层级 tree 需要写入/解析/down-walk 三个新组件。
- 结论：先拿"头相同 → 0 传输"的 100x 收益（今天连这一步都没有），层级 tree 留作 P3 可选优化。

### 3.3 写入与清理

- **原子写**：`tmp` → `rename`（复用 [TwinWorkspace.kt:97](../plugins/plugin-memory-twin/src/main/kotlin/com/mengpaw/plugin/memorytwin/TwinWorkspace.kt) 的既有写法，含"删除前不预删"的 P2 修复经验）。
- **幂等**：内容寻址 + 写入即覆盖同名对象，无需任何锁。
- **GC**（`memory.gc`，默认每日空闲执行）：从 `heads` + `local` 标记清扫；保留策略默认 `30 天 / 200 快照`，两者取宽；库体积超 64MB 在 `twin.status` 告警。
- **损坏防御**：对象解压/解析失败 → 记 `ErrorCollector` + 视为缺失重新拉取，不中断本轮。

---

## §4 协议 v0.3

### 4.1 消息

| 消息 | 方向 | 载荷 | 说明 |
|---|---|---|---|
| `SYNC_HELLO` | → 对端 | `{proto:"0.3", deviceId, ns, heads:[sha]}` | 单轮探测 |
| `SYNC_ACK` | ← 对端 | `{same:bool, heads:[sha], missingTrees:[sha], missingBlobs:[sha]}` | 相同则只回 `same:true` |
| `TREE_WALK` | → 对端 | `{ns, sha}` | 取 tree 对象 |
| `OBJ_GET` | → 对端 | `{ns, blobs:[sha]}` | 批量取 blob（base64+zlib） |

四个消息，全部请求-响应，沿用现有 `sendForResult` 与 20s 超时。**无 push 消息**（见 §2.3）。

### 4.2 时序

```
每轮（对每个 online peer）:
  HELLO{ws heads, dev heads}
    ├ same → 结束
    └ diff → ACK{missing…}
              → TREE_WALK × N（只取缺的 tree）
              → OBJ_GET × 1（批量取缺的 blob）
              → 合并（§5）→ 本地快照 → local ref 前进
```

`dev`（设备库存）命名空间与 `ws` 走完全相同的消息，只是 `ns` 不同——**不新增设备专用消息**。

### 4.3 鉴权

`SYNC_HELLO`/`SYNC_ACK`/`TREE_WALK`/`OBJ_GET` 全部加入：

- `AcpServer.processMessage` 的 `WS_MANIFEST, WS_PULL, REVOKE` 分支（要求 `PromptFirewall.isTrusted`）
- `AcpTransport.SENSITIVE_ACP_TYPES`（要求 peerId 与来源 IP 绑定）

否则等于开了一个未鉴权的工作区读取接口（现有 P0 修复语义）。

### 4.4 与 v0.2 双栈

`CapabilityCard.protocolVersion` 已存在（当前 `"0.2"`）。规则：

- 对端卡片 `protocolVersion >= 0.3` → 走 v0.3；
- 否则 → 走现有 `WS_MANIFEST`/`WS_PULL`（**冻结，不再演进**，仅保兼容）；
- 未收到卡片（未上报）→ 视为 v0.2。

### 4.5 base = local ref（不做图搜索）

三方合并的 `base` 取 `refs/<ns>/local`（上次成功同步的快照），**不做完整 DAG 的 LCA 搜索**：

- 语义正确：`local` 就是"双方上次一致的时刻"；
- 天然支持"离线三个月回来"：base 是三个月前那个头，ours/theirs 各自一条线；
- 实现只需读一个文件，不需要图遍历。

多 head：`heads` 是集合（P2P 网状下多 head 是常态），每次合并把双方 heads 全作为新快照的 parents，然后 heads 收敛为单值；分叉未合并时保留多个。

---

## §5 合并

### 5.1 流程

```
ours   = 当前磁盘文件集
base   = local ref 对应快照的 tree
theirs = 对端 head 的 tree
  ↓ 按 §5.2 逐文件策略
写入工作区 → 快照提交 → local ref / heads 前进
```

### 5.2 按类策略

| 类 | 变更形态 | 策略 | 冲突产物 |
|---|---|---|---|
| L | 两侧都改 | `diff3(base, ours, theirs)` | 文件内 `<<<<<<< ours` / `=======` / `>>>>>>> theirs`（用设备名）+ inbox 任务 |
| L·frontmatter | `---…---` 块 | **键级合并**：不同键取并集，同键按 (lamport, deviceId) 取一方并在块尾标注 | 不产生行级冲突 |
| M | 两侧追加 | **追加并集**：两侧新块都保留，按 (lamport, deviceId) 排序 | 无冲突 |
| M·专项 | `twin-model-rules.json` | 按 `rule.id` 并集，同 id 取较新 | 无冲突 |
| D | 单写者 | 直接用较新快照；**若同一 deviceId 出现双写 → 拒绝合并 + 告警** | 暴露为身份故障，不静默覆盖 |
| 删除 vs 修改 | 一方删、一方改 | **保留修改方**，落 `{name}.kept-<device>.md` + inbox 任务 | 永不静默删 |
| 兜底 | 非 UTF-8 或 >256KB | 不合并，取较新一方 + `.conflict` 备份（沿用现状） | `.conflict` 文件 |

### 5.3 冲突交回 Agent

冲突不弹 UI，写 `{agent}/inbox/merge_conflict_<时间>.md`：

```markdown
# 工作区合并冲突（2 个文件）
- memory/memory.md — 1 处（约 12 行）
- soul.md — 1 处
请直接编辑这些文件消除冲突标记（<<<<<<< / ======= / >>>>>>>），完成后无需额外操作。
```

提示词里加一条常驻指引：**"文件里出现冲突标记时，先裁决冲突再执行任务"**（写在记忆段，不放前缀区）。

### 5.4 Lamport 时钟

`{AGENTS}/twin/clock` 单行整数：

- 本地提交前 `clock += 1`；
- 收到对端快照时 `clock = max(local, remote) + 1`；
- 时钟文件损坏 → 回落 `wall/1000` 并在 `twin.status` 标注（不可静默）。

排序键统一 `(lamport, deviceId)`；`wall` 只用于显示。

---

## §6 记忆账本

### 6.1 条目信封（`ledger/<agent>/entries.jsonl`，只增）

```json
{
  "entryId": "sha256:…",
  "track": "mid",
  "file": "memory/memory_2026-10-22.md",
  "blockHash": "sha256:…",
  "project": "mengpaw",
  "agent": "MengPaw",
  "device": "7f3a…",
  "lamport": 1042,
  "wall": "2026-10-22T22:40+08:00",
  "session": "sess-…",
  "message": "msg-…",
  "supersedes": ["sha256:…"],
  "kind": "note|digest|revision",
  "sources": ["sha256:…"]
}
```

- **v1 内容归属**：条目只存 `blockHash`，正文仍在文件里（文件是接口）；blob 已由树持有，无额外存储。
- **条目同步**：`entries.jsonl` 作为 `ws` 命名空间的白名单文件（与 `twin-model-rules.json` 同机制），合并 = 按 `entryId` 并集。
- **元数据来源**：`device`/`agent`/`lamport`/`wall`/`session` 由运行时注入（`MemoryTwinPlugin.agentSessionId` 已有）；`project`/`supersedes`/`sources` 由 Agent 可选声明。

### 6.2 写入路径（Agent 零改动）

现有命令已经够用，**不新增写命令**：

| 现有命令 | 现状 | 账本挂钩 |
|---|---|---|
| `agent.memory.keep` | 写长期 `memory/memory.md` | L 类，无条目（长期不记账） |
| `agent.memory.record` | 追加中期分片 | **生成 mid 条目**（`blockHash` + 元数据） |
| `agent.memory.project.save` | 追加项目记忆 | 同上，带 `project` |
| `agent.memory.mid.edit` / `mid.rm` | 就地编辑/删除 | 改为**生成 `revision` 条目 + `supersedes` 指向被改块**，不物理破坏原块 |
| `agent.memory.rm` / `edit`（长期） | 就地编辑 | 保持现状（L 类靠三方合并） |

这带来一个顺手收益：现有提示词已经写着"**Agent 不主动编辑中期记忆**"（[PromptEngine.kt:126](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/llm/PromptEngine.kt)），与账本语义本来就一致——**改动面比预期小**。

### 6.3 归纳降级为视图

`DreamEngine` 改造要点：

```
现在：读全部中期分片 → 写 {date}_dream.md → 删除分片
改为：查账本（按日期/项目/未归纳）→ 写 {date}_dream.md（带 sources[]）→ 标记已归纳
```

- 删分片改为"标记 + 归档"：条目与 blob 已在对象库，内容不丢；文件可被 GC 视图清理。
- **`sources[]` 为强制字段**：归纳产物里每条要点必须指向至少一个 `entryId`；无法追溯的标 `unsourced`（提示词告知 Agent 该条可信度低）。
- **重跑幂等**：同一输入（同一批条目）重跑必须产出同序结论；LLM 输出非确定，因此**重跑不覆盖旧产物**，生成 `{date}_dream.v2.md` 并在索引里指向最新版本。

### 6.4 物化索引（降上下文压力的关键）

| 索引 | 内容 | 生成者 | 注入提示词 |
|---|---|---|---|
| `devices/index.md` | 每设备一行（见 §8） | 库存变更时 | ✅ |
| `memory/index.md` | 项目分组 + 每项目最近 3 条标题 + 条目计数 + 未归纳数 | 条目追加时 | ✅ |

索引是**生成物**（首行标注"自动生成，勿手改"），但作为普通工作区文件被 Agent 读取。

---

## §7 设备/能力库存

### 7.1 先修 deviceId

- 首次启动生成随机 UUID，持久化 `{AGENTS}/twin/device-id.json`：`{"uuid":"…","legacyFingerprint":"…"}`。
- 信任查找只看 `uuid`；`legacyFingerprint` **仅保留用于诊断/审计展示，不用于自动信任迁移**（自报的 legacy id 可被冒用，不能拿它换信任）。
- 升级到 UUID 身份需要**重新配对一次**（既有信任文件按 peerId 存，身份一变即失效）；因此必须与协议 v0.3 双栈升级同批做，见 §10.4。
- 硬件信息降级为字段，**不再参与身份**。

### 7.2 文件格式（`devices/<deviceId>.md`）

```markdown
---
deviceId: 7f3a-…            # 安装期 UUID
legacyFingerprint: 92c1…    # 仅诊断/审计展示，不用于信任迁移
deviceName: 荣耀平板
formFactor: TABLET
os: Android 15 (API 35)
cpuCores: 8
ramMB: 8192
storageFreeMB: 41200
screen: 2560x1600@320
sensors: [accel, gyro, light]
camera: [rear, front]
observedAt: 2026-10-22T22:41+08:00
schemaVersion: 1
status: active              # active | retired
agents:
  - name: MengPaw
    model: deepseek-flash
    contextTokens: 128000
    vision: true
    tools: true
    plugins: [browser, office, termux]
    sources: {model: measured, vision: declared}
---

# 荣耀平板

（人读补充，Agent 可改）
```

- **格式选 md + frontmatter**：Git diff 可读、Agent 能直接编辑、与工作区文档体系一致。单写者无合并，JSON 的结构化合并优势不存在。
- **单写者铁律**：工具层拒绝写 `devices/<非本机id>.md`；提示词写死"只能登记自己"。

### 7.3 库存 vs 运行时（必须切开）

| | 内容 | 载体 | 频率 |
|---|---|---|---|
| **库存**（进 Git） | 型号/形态/OS/CPU/RAM/磁盘/屏幕/传感器/相机/插件/模型/上下文/视觉/工具 | `devices/*.md` | 仅变更时 |
| **运行时**（不进 Git） | online / battery / busy / currentSession / 网络类型 | 现有心跳（30s，90s 判离线） | 秒级 |

现状 `CapabilityCard` 把两者混在一起（`RuntimeStatus`/`batteryLevel`/`networkType`），这是它无法成为 Git 产物的根因。

### 7.4 采集器 SPI

- kernel 定义 `DeviceCollector`（纯 JVM 接口 + `DeviceSnapshot` 数据类）；
- shell 注册 Android 实现（`Build`/`SensorManager`/`CameraManager`/`BatteryManager`），仿现有 `FleetPlatform.capabilityProvider` 注入模式；
- 触发：启动时 + 现有 `TwinCapabilityCollector.registerAutoCollect` 广播接收器 + 装/删插件、换模型；
- **有差异才提交**（对比 frontmatter 字段），无变化不写历史。

### 7.5 三套能力卡收敛

| 现存 | 处理 |
|---|---|
| `CapabilityCard`（twin，内存 + inbox） | 降级为**库存的传输信封**（字段基本不动，加 `schemaVersion`） |
| `FleetCapability`（fleet，内存 cache → `Notes/fleet_capabilities.md`） | `fleet.scan` 产物改为**从库存渲染**，不再 scan 覆盖写 |
| `twin.model` 的 ModelProfile | 读库存 `agents[].model` 段 + 证据库（`ModelEvidenceStore` 保留） |

`twin.route` 改为读库存：**离线设备也能参与评估**（标注"3 天前更新"），不再只认本次会话收到过上报的节点。

### 7.6 时效与退役

- 每个库存行带 `observedAt`；索引显示"X 天前"。
- 超过 30 天：默认**仍参与路由但标注"可能过时"**（可配置为排除）。
- 设备退役：`status: retired`（不删行，历史保留），与 `twin.lost` 联动。
- 新设备：配对时对端推自己那一行，本机**只落盘不修改、不补字段**。

---

## §8 提示词注入

### 8.1 注入点与顺序

在 `PromptSystemBuilder` 的 `## 你的长期记忆`（[PromptSystemBuilder.kt:357](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/llm/PromptSystemBuilder.kt)）之后、`pinnedSkillsBlock` 之前插入：

```
## 你的设备与能力（多端清单）
- 你寄存于：荣耀平板（TABLET, 8 核 / 8G）— deepseek-flash（128K, 视觉）
- 其余 2 端：
  - 台式机（DESKTOP, 16 核 / 32G, browser+office）· 3 天前更新
  - 手机（PHONE, 8 核 / 12G, 定位+相机）· 12 小时前更新
- 查详情：device.info <名称>；按能力找端：device.find <能力>
- 文件里出现冲突标记（<<<<<<< / ======= / >>>>>>>）时，先裁决冲突再执行任务。
- 你只登记自己那一行；其他端的环境由它们自己登记，不要替它们补写。
```

### 8.2 缓存指纹（易漏的坑）

- 注入的文档必须进入 `AGENT_DOC_FILES`（[PromptSystemBuilder.kt:409](../mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/llm/PromptSystemBuilder.kt)），否则 mtime 缓存永不失效。
- `devices/index.md` 在 `{AGENTS}/twin/` 下、**不在 agent 工作区**，需要独立的 `currentDeviceIndexFingerprint()` 参与缓存键。
- 新段放 docsBlock **末尾**，不打断前缀区（现有铁律："前插击穿前缀缓存"）。

### 8.3 Token 预算

| 段 | 预算 |
|---|---|
| 设备清单 | ≤ 8 端 × 1 行 ≈ 300 字符（≈ 150 token） |
| 记忆索引 | 每项目 1 行 + 最近 5 条标题 ≈ 400 字符 |
| 常驻指引 2 条 | ≈ 100 字符 |

超出即截断（沿用 `DOC_FULL_INJECT_CHARS`/`DOC_SNIPPET_CHARS` 机制）。

---

## §9 命令面

### 9.1 新增命令（9 条）

| 命令 | 风险级 | 说明 |
|---|---|---|
| `memory.log [n]` | LOW | 快照历史（时间/来源设备/变更文件数） |
| `memory.show <path> [snap]` | LOW | 某文件在某快照的内容 |
| `memory.diff [--to <snap>]` | LOW | 当前工作区 vs 快照的 unified diff |
| `memory.restore <path> [snap]` | MID | 恢复旧版本（含被删文件） |
| `memory.gc [--keep 30d]` | LOW | 对象清理 |
| `device.self` | LOW | 本机库存一行 + 寄存声明 |
| `device.list` | LOW | 全部设备（名称/形态/模型/时效/状态） |
| `device.info <name>` | LOW | 单端完整库存 |
| `device.find <capability>` | LOW | 按能力找端（如 `browser`、`vision`） |
| `device.refresh` | LOW | 重测本机并提交（有差异才写） |

命名不与现有冲突：现有为 `agent.memory.*`（18 条）与 `twin.*`（17 条）。

### 9.2 四源同步（新增命令必须一次改齐）

| 源 | 文件 |
|---|---|
| 命令索引 | `mengpaw-kernel/.../cli/BuiltinCommandIndex.kt` |
| 提示词常用命令（zh + en 两份） | `mengpaw-kernel/.../llm/PromptEngine.kt` |
| 开发指南命令表 | `MengPaw-Development-Guide.md` §5.1 |
| 索引覆盖测试 | `IndexCoverageTest` / `PromptGhostReferenceTest`（会直接失败） |

另需同步 `security/CommandRiskLevels.kt`（未登记风险级的命令会被门禁拒绝或落错档）。

> 注：`CLI.md` 已于 v0.34.3 整体移除，不再是同步源（历史文档提及属正常残留）。

---

## §10 拆改计划

### 10.1 依赖关系

```
P0 止血（哈希/落盘）── 无依赖，已完成
   │
   ├── P1 对象库 + 快照（纯 JVM，可单测，不接协议）
   │      ├── P2 协议 v0.3 + 合并（接传输，双栈）
   │      │      └── P3 记忆账本（entries + DreamEngine + 索引）
   │      │             └── P4 设备库存（依赖 store + collector SPI + 提示词）
   │      └── P3'（账本可先于 P2 做单机版，仅不跨端）
```

P1 与 P3（单机版）可并行；P2 是唯一动协议的点，风险集中在此。

### 10.2 P0 · 止血（0.5～1 天，当天见效）

| 动作 | 文件 | 验收 |
|---|---|---|
| 统一 64 位哈希（发/收同一 `shortHash()`，或全 64） | `TwinSyncProtocol.kt`、`TwinAcpHandler.kt` | 两轮同步中，第二轮 `send` 为空 |
| 内容相同不落盘（先比对再写，不写 tmp、不 `notifyDocChanged`） | `TwinWorkspace.kt` | 空闲 10 分钟，落盘 0 次 |

**量化收益**：空闲期 wire 从"每 60s 一个工作区"降到"每 60s 约 200B"；提示词缓存从"每分钟失效"降到"仅真变更时失效"。

### 10.3 P1 · 对象库与快照（2～3 天）

新增（`mengpaw-kernel/src/main/kotlin/com/mengpaw/kernel/memory/`，全部纯 JVM、可 `:mengpaw-kernel:test` 直测）：

| 文件 | 职责 | 约行数 |
|---|---|---|
| `SnapshotTypes.kt` | 数据模型（blob/tree/snapshot/ref） | 120 |
| `SnapshotStore.kt` | 对象读写、原子写、GC、损坏防御 | 350 |
| `TextDiff.kt` | Myers diff + unified 渲染 | 180 |
| `MemoryHistoryCommands.kt` | `memory.log/show/diff/restore/gc` | 250 |

改动：`DataPaths.kt`（新增 `TWIN_STORE`/`TWIN_DEVICES`/`TWIN_CLOCK`/`DEVICE_ID`）、`PipelineManager.kt`（注册 `memory` 命名空间）、`BuiltinCommandIndex.kt`、`CommandRiskLevels.kt`、`PromptEngine.kt`、开发指南 §5.1。

验收：单机可 `memory.log` 看历史、`memory.restore` 捞回被删文件；对象只存一份（同内容不同路径去重）。

### 10.4 P2 · 协议 v0.3 + 合并（3～4 天）

| 动作 | 文件 |
|---|---|
| 四个新消息类型 | `AcpProtocol.kt` |
| 鉴权（信任 + IP 绑定） | `AcpServer.kt`、`AcpTransport.kt` |
| v0.3 同步与合并驱动（新文件，v0.2 路径冻结） | 新增 `TwinSyncV3.kt`（~380） |
| 消息分派 + 双栈判定 | `TwinAcpHandler.kt`、`TwinCapability.kt` |
| 三方合并与策略表 | 新增 `TreeMerge.kt`（~350） |
| 文件分类（L/M/D/S） | `TwinWorkspace.kt` |
| 删除传播 + 冲突 inbox | `TwinSyncEngine.kt` / `TwinWorkspace.kt` |
| 删除死代码 `TwinDiscovery`（发现只保留 framework 通讯录 + 手动 IP） | 删 `TwinDiscovery.kt` |
| deviceId 改安装期 UUID + legacy 兜底（从 P0 移入） | `AcpCrypto`、`AppRootTwin.kt`、新 `device-id.json` |

> **为什么从 P0 移到 P2**：身份从指纹改成 UUID 会让所有既有配对即时失效（信任文件按 peerId 存），
> 必须与协议 v0.3 双栈升级同批做，一次重配；单独在 P0 改等于把所有在线设备打到未配对。

验收：三端网状（A↔B↔C，A 不直连 C）24h 后工作区逐字节一致；断网 7 天回来只产生预期冲突；同型号两机不互相覆盖。

### 10.5 P3 · 记忆账本（2～3 天）

| 动作 | 文件 |
|---|---|
| 条目生成（record/project.save 挂钩） | `AgentDocsMemory.kt` |
| 编辑转 `supersedes` | `AgentMemoryMutateCommands.kt` |
| 归纳改读路径（查账本、带 sources、不删分片） | `DreamEngine.kt` |
| 记忆索引生成 | 新增 `MemoryIndexWriter.kt`（~150） |
| 索引注入 | `PromptSystemBuilder.kt`、`PromptEngine.kt`（冲突指引） |

验收：双端各追加一段中期记忆 → 合并后两段都在且顺序稳定；归纳产物 100% 带 `sources`；重跑不覆盖旧产物。

### 10.6 P4 · 设备库存（2～3 天）

| 动作 | 文件 |
|---|---|
| 库存模型与 md 解析 | 新增 `device/DeviceInventory.kt`（~250） |
| 库存读写 + 索引 + 时效 | 新增 `device/DeviceInventoryStore.kt`（~200） |
| 采集器 SPI | 新增 `device/DeviceCollector.kt`（~80） |
| 命令 | 新增 `device/DeviceCommands.kt`（~220） |
| Android 采集实现 + 注册 | 新增 `shell/.../DeviceCollectorImpl.kt`（~150）、`MainActivity` |
| 路由改读库存 | `TwinRouter.kt`、`TwinCapabilityCommands.kt` |
| 三套能力卡收敛 | `TwinCapability.kt`、`FleetCapability.kt`、`FleetExecutor.kt` |
| 索引注入 | `PromptSystemBuilder.kt` |

验收：新设备 3 分钟内出现在所有端索引；重启后库存不丢；`device.find browser` 能定位到有浏览器的端；`fleet.scan` 与库存不再各说各话。

### 10.7 总计

| 项 | 规模 |
|---|---|
| 新增文件 | 约 11 个（kernel 9 + shell 1 + 插件 1） |
| 新增代码 | 约 2,400 行（含注释，均 ≤400 行/文件） |
| 改动文件 | 约 18 个 |
| 删除 | `TwinDiscovery.kt`、v0.2 路径后续下线 |
| 工期 | 10～14 个工作日（P0 当天可交付） |

---

## §11 测试清单

新增（kernel 纯 JVM 优先）：

| 测试 | 覆盖 |
|---|---|
| `SnapshotStoreTest` | blob 去重/幂等/损坏对象防御/GC 保留引用/原子写 |
| `SnapshotTypesTest` | 同内容同 tree 哈希；空树；parents 影响 snap 不影响 tree |
| `TreeMergeTest` | L 类 diff3 / frontmatter 键级 / M 类并集 / D 类双写告警 / 删除-修改 / 大文件兜底 |
| `TextDiffTest` | 空文件、仅末行变更、CRLF 归一、中文标点 |
| `MemoryLedgerTest` | append→entry、edit→supersedes、排序稳定、`sources` 必填校验 |
| `DreamViewTest` | 归纳带 sources、重跑不覆盖、幂等排序 |
| `DeviceInventoryTest` | 解析/序列化、单写者拒绝、staleness、index 渲染 |
| `TwinSyncV3Test` | HELLO 判等、超时、坏载荷、双栈判定 |

需随分类调整：`TwinWorkspaceTest`（L/M/D/S 分类）、`TwinAcpHandlerTest`（新消息类型）、`TwinSyncEngineTest`。

全量口径：`:mengpaw-kernel:test` → `:plugin-memory-twin:testDebugUnitTest` → shell/browser 相关，最后全量 `test`（开发指南 §3.7 快照需更新）。

---

## §12 兼容、回滚与风险

### 12.1 兼容

| 场景 | 处理 |
|---|---|
| 对端仍是 v0.2 | 按卡片 `protocolVersion` 走冻结的全量路径 |
| 本机升级已有配对 | 身份改 UUID 后既有信任失效，**需重新配对一次**；`legacyFingerprint` 仅用于诊断/审计展示，不用于自动信任迁移（自报 id 可被冒用） |
| 旧 `.conflict` 备份 | 保留不清理；新模型不再产生（除兜底分支） |
| 旧 `memory/backup/` | 保持排除，不迁移、不删除 |

### 12.2 回滚

- 协议层：删除 v0.3 分支即回到 v0.2（`local`/`heads` ref 文件不影响旧路径）。
- 对象库：删 `{AGENTS}/twin/store/` 即回到无历史状态（工作区文件不受影响）。
- 索引：删 `index.md` 后下次自动重建。
- 账本：`entries.jsonl` 只增，回滚不影响工作区文件内容。

### 12.3 风险

| 风险 | 缓解 |
|---|---|
| 提示词前缀缓存被新段击穿 | 新段放末尾 + 独立 mtime 指纹（§8.2） |
| 对象库膨胀 | GC 默认 30 天/200 快照 + 体积告警 |
| 冲突标记进入提示词 | 提示词常驻一条"先裁决再执行"；冲突文件数在 `twin.status` 可见 |
| CRLF/中文标点造成 diff 噪音 | 统一 LF 归一（项目已有 CRLF 教训） |
| 双栈长期并存腐化 | v0.2 路径冻结、只修 P0 级缺陷，v0.5 后删除 |
| 归纳重跑结果不一致 | 不覆盖旧产物，版本化 `{date}_dream.vN.md` |
| Lamport 时钟损坏 | 回落 wall + 告警，不静默 |

### 12.4 待你拍板（文档已按推荐值写）

| 项 | 推荐值 |
|---|---|
| 库存格式 | md + frontmatter（可读可 diff） |
| 过期库存（>30 天）是否参与路由 | 参与，但标注"可能过时" |
| 中期是否彻底禁就地编辑 | 禁止；`edit` 转 `supersedes` 条目 |
| 提交粒度 | 按写入批次（一次 Agent 写入 = 一个快照/条目批次） |
| 多 head | 保留集合，UI 只显示"最后一次同步" |

---

## §13 总验收标准

| 指标 | 目标 |
|---|---|
| 空闲期 wire | 每端每轮 ≤ 300B（现状：一个工作区/轮） |
| 空闲期落盘 | 0 次/10 分钟 |
| 三端网状 24h | 工作区逐字节一致 |
| 断网 7 天回归 | 追加型文件零冲突；L 类冲突 ≤ 1 个文件且有 inbox 任务 |
| 归纳可追溯 | 产物要点 `sources` 覆盖率 100%（无法追溯必须标 `unsourced`） |
| 设备感知 | 新设备 ≤ 3 分钟出现在所有端；`device.self` 与真实设备一致 |
| 提示词增量 | 设备段 ≤ 150 token，记忆索引 ≤ 200 token |
| 测试 | 新增用例 ≥ 60，全量 0 failures |

---

## 附录 A · 与现有实现的对应关系

| 现有 | 新归属 |
|---|---|
| `TwinWorkspace.buildManifest/applyWorkspaceFile` | 保留扫描/排除/路径消毒；LWW 换为合并 |
| `TwinSyncProtocol`（WS_MANIFEST/WS_PULL） | v0.3 新实现；旧路径冻结 |
| `TwinAcpHandler`（8 类型） | +4 类型；旧 handler 保留至 v0.5 |
| `TwinSyncEngine`（心跳/QoS/PeerRegistry） | 保留不动 |
| `TwinRouter`（内存能力卡） | 改读库存 |
| `CapabilityCard` | 库存投影（传输信封） |
| `FleetCapability` | 库存投影（`fleet.scan` 渲染） |
| `DreamEngine` | 读账本、带 sources、不删分片 |
| `TwinDiscovery` | 删除（未被消费的死通道） |

## 附录 B · 实施时需同步的文档

1. `MengPaw-Development-Guide.md` §3.5.1（孪生架构）、§5.1（命令表）、§3.7（测试快照）、版本号与日期
2. `docs/INDEX.md`（本文件条目）
3. `CHANGELOG.md`（实施版本条目）
4. `docs/lessons.md`（若踩到新坑）
