# 记忆孪生 Git 化 · 终审报告（2026-10-23）

> 审查范围：`git log --oneline 965e5cfe..HEAD`（P0 修复 → P1 对象库 → P2 协议 v0.3 + 三方合并 → P3 账本 → P4 库存 → P4.5 能力卡收敛，共 14 个提交，94 文件 +10,634/−625）
> 方法论：`closure-audit-12q`（三层二十一问）+ `code-review-9d`（九维）；机器门禁先行，只修 P0（数据丢失 / 安全绕过 / 必崩）。

---

## 门禁结果（三条命令 + EXIT + 用例数）

| 命令 | EXIT | XML 实测（非 BUILD SUCCESSFUL 推断） |
|---|---|---|
| `.\gradlew.bat :mengpaw-kernel:test --console=plain` | **0** | 111 个结果文件 / **875 用例** / failures 0 / errors 0 / skipped 2 |
| `.\gradlew.bat :plugin-memory-twin:testDebugUnitTest --console=plain` | **0** | 14 个结果文件 / **102 用例** / failures 0 / errors 0 / skipped 0 |
| `.\gradlew.bat :mengpaw-shell:compileDebugKotlin --console=plain` | **0** | Kotlin 编译通过（无 error/warning 输出） |

基线复核：kernel 873 → **875**（+2，本次 P0 修复的内核侧回归 `SnapshotStoreTest.对端基准…` / `GC 保留对端基准快照`）、plugin 100 → **102**（+2，P0 回归 `TwinThirdPeerMergeTest`）。四条门禁外产物齐备：`kernel/memory/SnapshotStore.kt`、`kernel/memory/MemoryLedger.kt`、`kernel/device/DeviceInventoryStore.kt`、`plugin-memory-twin/.../TwinSyncV3.kt`。

---

## 十二问结论表（21 行）

| # | 状态 | 证据（file:line） | 分级 |
|---|------|------------------|------|
| 1.1 Agent 知道功能存在 | ✅ | 提示词中文/英文双份：`PromptEngine.kt:157`、`PromptEngine.kt:270`；设备段注入 `PromptDeviceSection.kt:24`；记忆索引段 `PromptMemorySection.kt:22` | - |
| 1.2 知道资源在哪 | ✅ | `DeviceCommands.kt:57`（预期文件路径）、`DeviceCommands.kt:80`；`PromptDeviceSection.kt:64`（`cat …/devices/index.md`）；`PromptMemorySection.kt:33`（`cat …/memory/index.md`） | - |
| 1.3 知道用什么命令 | ✅ | 6 条 `memory.*` + 5 条 `device.*` 全部注册 `PipelineManager.kt:169/172`，全部入索引 `BuiltinCommandIndex.kt:231-242`，全部登记风险级 `CommandRiskLevels.kt:33-43` | - |
| 1.4 有简介/可搜索 | ✅ | 每条索引带中文简介 + 中英关键词分组 `BuiltinCommandIndex.kt:231-242`；`IndexCoverageTest` 双向锁死（实现↔索引） | - |
| 1.5 成功后给指引 | ✅ | `MemoryHistoryCommands.kt:188`（已恢复 + 快照号）、`DeviceCommands.kt:213-216`（文件路径 + "将随 v0.3 同步"）、`AgentMemoryLedgerHook.kt:131`（回传 entryId） | - |
| 1.6 权限边界 | ⚠️ | `memory.restore` 定级 MID（`CommandRiskLevels.kt:94`）只换来"权限等级 ≥ TRUSTED"（`RiskGate.kt:42-46`）；索引/提示词/开发指南都写"**需 reason 意图声明**"，但它**不在** `HighRiskCommandGate.HIGH_RISK`（该表 34-76 行无此项）→ reason 从未被校验 | P1 |
| 1.7 读到的指令还成立吗 | ⚠️ | 现存的 `memory.*`/`device.*`/17 条 `twin.*` 全部真实注册（`PipelineManager.kt:169/172`、`MemoryTwinPlugin.kt:148-166`、`metadata.commands:48-57`），**零幽灵**；但①幽灵守护测试的命名空间白名单只有 `self/agent/plugin/evolution`（`PromptGhostReferenceTest.kt:138`）→ 提示词里的 `memory.*`/`device.*` 引用**无守护**；②`twin-guide.md` 未补新命令，且 `twin.sync` 仍写"接收/发送/冲突数"（`twin-guide.md:20`，v0.3 纯 pull 时 `filesSent` 恒 0） | P1 |
| 2.1 固定独立存储目录 | ✅ | `DataPaths.kt:118-127`（`TWIN_DIR/STORE/LEDGER/DEVICES/CLOCK` + `ledgerFile()` 消毒）；布局与设计 §3.1 一致（objects/refs/tmp、refs/<ns>/heads+local） | - |
| 2.2 Agent 能验证状态 | ⚠️ | 有 `memory.log/show/diff`、`memory.gc` 回报体积（`MemoryHistoryCommands.kt:208-212`）、`device.list` 时效；但 `twin.status` 的统计全在内存 `StateFlow`（`TwinLifecycleCommands.kt:112,122-128`）→ 重启后"上次同步：从未"，且对象库体积/账本条数/未归纳数/冲突文件数都不在 status 里（设计 §3.3 的 64MB 告警也未落地） | P1 |
| 2.3 UI 操作反馈 | N/A | 设计 §5.3 明确"冲突不弹 UI"，改走 `{agent}/inbox/merge_conflict_*.md`（`TwinSyncCommitWriter.kt:126-145`）+ 命令输出 | - |
| 2.4 操作后自动生效 | ✅ | 落盘后挂钩：中期/项目写入→账本条目+索引重建（`AgentDocsMemory.kt:197-200,276-279`）、编辑/撤回→revision+索引（`AgentMemoryLedgerHook.kt:126-130`）、库存写入→索引重建（`DeviceInventoryStore.kt:117`）、账本同步→索引重建（`TwinSyncSpaces.kt:189`） | - |
| 2.5 删除/禁用两种粒度 | ⚠️ | 记忆条目=追加 tombstone（原块保留，`AgentMemoryMutateCommands.kt:139-155`），文件=`.deleted` 备份（`TwinWorkspace.kt:173`）；但**设备退役**只有 `status: retired` 字段（`DeviceInventory.kt:43,86`），没有命令/工具能设置，只能手改 md | P2 |
| 2.6 异常路径处理 | ⚠️ | 损坏防御到位（对象 sha 校验 `ObjectStore.kt:61`、坏行跳过 `MemoryLedger.kt:250-276`、`.conflict`/`.kept-` 兜底）；但 `ObjectStore.write` **吞掉写失败仍返回 sha**（`ObjectStore.kt:38-52`）→ `commit` 可能记下一个对象未落盘的快照（heads 指向缺失对象） | P1 |
| 2.7 双通道一致 | ⚠️ | 路径保护一致且更严：同步写入口 `TwinWorkspace.sanitizeRelPath:44`（拒绝对/盘符/`..`），`memory.restore` 走 canonical 边界校验（`MemoryHistoryCommands.kt:274-283`）；`device.refresh` 单写者校验在 store 层（`DeviceInventoryStore.kt:96-98`）。**不一致点**：v0.3 落盘传 `Long.MAX_VALUE` 使 `lastModified` 冲突分支恒不可达（`TwinSyncSpaces.kt:45` → `TwinWorkspace.kt:116`），而 `memory.restore` 无任何冲突保护、可写 `inbox/`/`dialog/` 等排除目录的 `.md` | P2 |
| 3.1 外部依赖备份 | N/A | 纯本地对象库 + P2P 直连，无第三方存储/镜像依赖（设计 §2.4 明确不用 JGit） | - |
| 3.2 失败给替代方案 | ✅ | 同步失败带建议（`TwinSyncV3.kt:135`）；采集器未注册给出注册点（`DeviceCommands.kt:196`）；对象库缺失回退重新拉取（`TwinSyncV3.kt:232-234`） | - |
| 3.3 版本信息传递 | ✅ | 协议版本随能力卡宣告（`TwinCapability.kt:202`）并在 `twin.status` 显示（`TwinLifecycleCommands.kt:118`）；库存带 `schemaVersion`（`DeviceInventory.kt:34`）；`device.info` 展示 `observedAt` + 时效 | - |
| 3.4 网络受限场景 | ✅ | QoS METERED 暂停自动同步（`TwinSyncProtocol.kt:152-155`）、手动 `twin.sync` 兜底；每命名空间独立失败不互相拖死（`TwinSyncV3.kt:115-122`） | - |
| 3.5 数据完整性 | ⚠️ | 原子写全部 `tmp→rename`（`ObjectStore.kt:94-120`、`DeviceInventoryStore.kt:113-116`、`MemoryIndexWriter.kt:90-94`、`DeviceIndexWriter.kt:92-95`）；refs 原子写；`local`/heads 落盘持久（`SnapshotRefs.kt`）；账本三重上限 + 坏行不中断。缺口：①`twin.status` 统计不持久（见 2.2）②`gc(ns)` 的删除面是**全部对象**，只按本 ns 种子保活 → 其他 ns 的老 head 可能被清（`SnapshotStore.kt:237-263`）③账本"扫描→合并→替换"窗口内新追加条目会被覆盖（见 P1-6） | P1 |
| 3.6 降级自动化 | ✅ | v0.2 双栈（无卡片/解析失败→0.2，`TwinSyncV3.kt:60-85`）；命名空间不支持→本轮跳过不中断；无 base→"本机为主 + `.conflict`"；对象损坏→当缺失重拉 | - |
| 3.7 保护链判定命中 | ⚠️ | 4 个新消息**双门齐备**：信任门 `AcpServer.kt:283-286`、`SENSITIVE_ACP_TYPES` `AcpTransport.kt:297-301`，且改成"先校验后绑定"（`AcpTransport.kt:285-289`）；17 条 `twin.*` 风险级齐全（`CommandRiskLevels.kt:46-60,96-97`）。缺口：①`memory.restore` 的 reason 判定不在 `HighRiskCommandGate.HIGH_RISK`（声明与实现脱钩，见 1.6）②D 类单写者保护在**同步路径不命中**：合并用的是"传输侧身份"而非库存行归属（`TreeMerge.kt:164-171`），已配对对端可覆盖任意 `devices/*.md`（含本机行）；对应单测以"两侧同 deviceId"自证（`TreeMergeTest.kt:199-206`），生产链路不可达 | P1 |

**统计**：✅ 11 / ⚠️ 8 / ❌ 0 / N/A 2。

---

## 九维结论（P0 / P1 / P2 + 亮点）

### P0 必修（已修，1 项）
1. **三方合并 base 用命名空间级 `local` → 第三端首次同步会删除本机真实记忆文件**（数据丢失）。详见下节"已修 P0"。

### P1 应修

| # | 维度 | 问题 | 证据 | 后果 |
|---|------|------|------|------|
| P1-1 | 健壮性/安全 | D 类"单写者"在同步路径不命中：判据是传输侧 deviceId 是否相同，而不是 `devices/<id>.md` 的行归属 | `TreeMerge.kt:164-171`、`TwinSyncSpaces.kt:94-117`、`DeviceInventoryStore.kt:96-98` | 已配对对端只要 lamport 更高（对端自报）即可覆盖本机库存行（含 `deviceName` 等进提示词的字段）；D 类双写告警实际不可达（测试 `TreeMergeTest.kt:199-206` 为构造态） |
| P1-2 | 安全（保护链） | `memory.restore` 声明"MID 需 reason"，但既不在 `HIGH_RISK` 表也无 reason 参数解析 | `CommandRiskLevels.kt:94` vs `HighRiskCommandGate.kt:34-76`；`MemoryHistoryCommands.kt:152-193` | 被 prompt 注入的 Agent 只要有 TRUSTED 权限即可无声明覆盖记忆文件，审计无 reason |
| P1-3 | 1.7 守护 | 幽灵引用守护的命名空间白名单缺 `memory`/`device`（以及 `twin` 属插件域） | `PromptGhostReferenceTest.kt:138` | 提示词里的 `memory.*`/`device.*` 改名后不会有测试失败 → 下次必然腐化（v0.47.x 教训） |
| P1-4 | 可观测 | `twin.status` 统计不持久：重启即"上次同步：从未"，无对象库体积/账本计数/冲突数 | `TwinLifecycleCommands.kt:112,122-128` | Agent 与人都无法在重启后判断"上次同步是否成功"，只能翻 `memory.log` |
| P1-5 | 数据完整性 | `gc(ns)` 只按本 ns 的 heads/local/base 做种子，却删除**全库**不可达对象 | `SnapshotStore.kt:237-263` | 其他命名空间 >30 天且不在最近 200 快照内的 head 会被清 → 该 ns 历史断链（文件仍在，需重新拉取） |
| P1-6 | 并发 | 账本"扫描 ours → 合并 → 锁内整文件替换"：窗口内新追加的条目会被合并结果覆盖 | `TwinSyncSpaces.kt:183-193`、`TwinSyncCommitWriter.kt:29-50`、`MemoryLedger.kt:204-221` | 账本少一条（正文仍在 md 文件里），索引/归纳漏一条；建议锁内重读并集而非替换旧结果 |
| P1-7 | 健壮性 | `ObjectStore.write` 写失败只记 ErrorCollector，仍返回 sha | `ObjectStore.kt:38-52` | `commit()` 会记录一个对象未真正落盘的快照（heads 指向缺失对象，readBlob 返回 null） |
| P1-8 | 可维护性 | `MemoryLedger.kt` 411 行 > 400 红线 | `MemoryLedger.kt`（wc=411） | 违反项目红线；建议把查询/并集段拆到 `MemoryLedgerQuery.kt` |
| P1-9 | 可读性/健壮性 | `splitBlocks` 以任意 `## ` 切块：记忆正文自带二级标题时块哈希与记账 hash 不一致 | `MemoryLedger.kt:386-388`、`resolveBlock:399-407` | 归纳产物显示"(正文不可解析)"，`mid.edit/rm` 匹配错位；建议按 `## HH:mm:ss` / 文件尾块形态切分 |
| P1-10 | 安全（路径卫生） | 对端自报 `deviceName` 未消毒就拼进备份文件名 | `TwinWorkspace.kt:119,173`、`TwinSyncSpaces.kt:124` | `.deleted.`/`.conflict.` 目标可能含 `/`（renameTo 多失败→删除被拒），非写入绕过但属输入卫生缺陷 |

### P2 可后续

- **死代码**：`MemoryLedger.unionMerge`（`MemoryLedger.kt:371`，仅测试调用）、`ManifestBlob/toFileMap/toManifestBlobs`（`SnapshotTypes.kt:19,53,57`，仅 `SnapshotTypesTest` 调用）—— 与设计"账本并集只有一条规则"相悖，容易被误当第二实现。
- **重复实现**：`tmp→rename` 原子写分散 6 处（`TwinWorkspace.kt:132-152`、`AgentMemoryLedgerHook.kt:138-153`、`DeviceInventoryStore.kt:113-124`、`DeviceIndexWriter.kt:92-98`、`MemoryIndexWriter.kt:90-99`、`TwinSyncSpaces.kt:101-105`），建议统一走 `ObjectStore.moveAtomic`。
- **补全度**：设备退役无命令（只能手改 md，`DeviceInventory.kt:86`）；`twin.status` 无冲突文件数。
- **文案过时**：`MemoryTwinPlugin.kt:19` 仍写"LWW 冲突备份"；`twin-guide.md:20,33` 仍按 v0.2 描述同步内容与收发计数。
- **兼容无测试**：`CapabilityCard.schemaVersion` 增字段后"旧卡仍可解析"靠 `ignoreUnknownKeys`（`TwinCapability.kt:47-48`）+ 默认值，但**无回归用例**；建议补一条老卡 JSON 解析用例。
- **噪音**：`spaces()` 固定三命名空间（`TwinSyncV3.kt:150`），对端只支持 ws 时每轮都会产生 `unknown_ns` 错误字符串（不中断，但脏日志）。
- **内存上限**：`MAX_BLOBS_PER_GET=2048` × 单对象 64MB 的理论上限（`SyncV3Messages.kt:39`、`ObjectStore.kt:27`）—— 与 v0.2 同风险，非本次新增。

### 亮点（值得保留）

1. **跨组件载荷契约测试** `TwinWireContractTest`（`buildManifestPayload`/handler 产出 → `parseManifestResponse` 消费）：这才是防"单边各测各的"导致 P0 形态漂移的正确姿势。
2. **保护链"先校验后绑定"**：`AcpTransport.authorizeAndBind:285-289` 修掉了"先 bindPeerIp 再判 isPeerFromBoundIp"恒真漏洞，且有 `AcpIpBindingTest` 锁定；4 个新消息同时进信任门与 IP 绑定表。
3. **宽进严出的协议解析**：`SyncV3Messages.parse*` 对非法 objectId / 非 base64 / 坏 JSON 一律过滤或返回 null，绝不抛给同步主循环。
4. **双栈是真测的**：0.2 对端必须首条 `WS_MANIFEST`、0.3 对端必须首条 `SYNC_HELLO`（`TwinSyncV3Test.kt:324-356`），不是"函数返回 true"式自证。
5. **删除永不静默**：`.deleted.<device>` 备份 + inbox 提示（`TwinWorkspace.kt:164-186`），且删除-修改冲突保留修改方（`TreeMerge.kt:256-266`）。
6. **并发集合用对**：`peers` 为 `ConcurrentHashMap`（`TwinSyncEngine.kt:53`）、账本 `append`/`replaceFromSync` 同锁（`MemoryLedger.kt:171,204`）、索引 `rebuild` `@Synchronized`。
7. **库存 vs 运行时切干净**：电量/在线不进库存，`observedAt` 不参与"有差异才写"（`DeviceCommands.kt:205,227-228`）。

---

## 已修 P0

**1 处：三方合并 base 未按对端隔离 → 第三端首次同步删除本机真实记忆文件（数据丢失）**

- **现象（修复前实测）**：本机已与 peer-b 同步过（`local` = 共同快照），随后首次与 peer-c 同步；peer-c 的树里只有 `soul.md`（新设备没拿到 `memory/memory_2026-10-22.md`）→ 该分片被判成"对端已删除"→ `removeWorkspaceFile` 把本机分片搬成 `.deleted.peer-c`，并在下一轮把删除传播给 peer-b。新增回归用例在修复前红在 `TwinThirdPeerMergeTest.kt:126`（"本机中期记忆分片必须还在"）。
- **根因**：base 取命名空间级 `refs/<ns>/local`（设计的"上次成功同步头"），但同步是网状多对端 —— 对**从未同步过的第三端**而言它并不是共同祖先；同时设计 §5.2 的"无共同基准 → 本机为主 + `.conflict` + inbox"保护因此失效。
- **修法（最小语义修复，不重构协议）**：
  - 新增按对端的基准 `refs/<ns>/base-<peer>`：`SnapshotRefs.base/setBase/bases`（`SnapshotRefs.kt:54-77`），`SnapshotStore.baseRef/setBaseRef/baseRefs` 转发（`SnapshotStore.kt:159-168`）。
  - base 取"与该对端上次一致的那个快照"（`TwinSyncCommitWriter.kt:57-68`）；缺基准 = 与该端从未同步过 → 走"无共同基准"分支，绝不删本机文件。
  - 基准写入用**对端候选头**（共同祖先）而非刚造的合并提交（`TwinSyncCommitWriter.kt:76-101`），并对"合并结果与本机既有 head 内容相同"的情况**不再造新快照**（`TwinSyncV3.kt:208-220`）—— 否则对端未拉到新提交时每 60s 都会新增一个合并提交互刷。
  - `local` 退化为历史/GC 种子；`gc` 增加反方向保活：对端基准快照也是种子（`SnapshotStore.kt:244-246`）。
  - 为守住 400 行红线：refs 层拆到 `SnapshotRefs.kt`（SnapshotStore 387→381 行）、落盘/提交层拆到 `TwinSyncCommitWriter.kt`（TwinSyncV3 453→335 行）。
- **回归测试（5 条）**：
  - `TwinThirdPeerMergeTest.与第三端首次同步不得删除本机已有文件`：首轮不删、无 `.deleted.peer-c`、`baseRef(peer-b)` 为空 / `baseRef(peer-c)` 成立、第二轮与第三轮**不新增快照**（防互刷）。
  - `TwinThirdPeerMergeTest.已建立基准的对端仍按三方合并处理删除`：**删除传播没被修坏**（有基准的对端删文件仍然传播、留 `.deleted`）。
  - `SnapshotStoreTest.对端基准按 peer 隔离且可持久化`：按 peer 隔离、同 peer 覆盖、非法 peer 名不越界。
  - `SnapshotStoreTest.GC 保留对端基准快照`：heads 清空后基准快照仍被保活。
  - 既有 2 条用例按新语义显式建立基准（`TwinSyncV3Test.kt:160`、`TwinSyncV3Test.kt:291`，属语义升级非放宽断言）。
- **迁移影响（已知）**：升级后每个对端的**首轮**同步按"无共同基准"处理 —— 内容一致的文件零动作，仅"双方不同"的文件各留一份 `.conflict` + 一条 inbox 提示，**不删任何文件**；第二轮起恢复三方合并。

---

## 已知取舍（不重复报为缺陷）

1. 设备身份仍是指纹（`SHA-256(Build.FINGERPRINT)[:32]`），同型号同 ROM 会串台；UUID 迁移留 P5，需用户在场重新配对（`DeviceInventoryStore.kt:25-27`、`DeviceInventory.kt:12-13`）。
2. `devices/index.md` 各端本地生成、不参与 `dev` 命名空间同步（视图 + 相对时效，同步会抖动）—— `DeviceIndexWriter.kt:16-22`、`TwinSyncSpaces.kt:57-62`。
3. 首次/无共同基准同步 → "本机为主 + 对端内容 `.conflict` + inbox"（`TreeMerge.kt:133-143`）；本次 P0 修复正是让这条对**每个**对端首轮生效。
4. 头收敛取**并集**，不丢弃本地 head（`TwinSyncCommitWriter.kt:107-128`）。
5. `mid.rm` / `project.rm` 追加 tombstone、原块保留在文件里；账本 `supersedes` 保证它不再被归纳（`MemoryLedger.kt:318-337`）。
6. `twin-discovery` 已删除，发现只走 framework 通讯录 + 手动 IP（`TwinLifecycleCommands.kt:52-56`）。
7. `agent.memory.*.edit` 后的新内容不会自动再归纳（需重新 `record`）—— `MemoryLedger.kt:322-328`。
8. `dev` 命名空间的 D 类合并"取较新一方"（lamport 优先）在**双方各自只写自己那一行**的前提下不会互相覆盖；跨端覆盖属 P1-1 的显式缺口，不是取舍。

---

## 遗留缺口与建议顺序

**先修 P1（按"数据正确性 > 安全 > 可观测"排序）**

1. **P1-6 账本锁内并集**：`replaceFromSync` 改为"接收对端字节 → 锁内重读本机 → 并集 → 原子替换"，消掉扫描窗口；顺带把 `TwinSyncSpaces.kt:183-193` 的预比对挪进锁内。
2. **P1-7 对象写失败上抛**：`ObjectStore.write` 返回 `null`，`SnapshotStore.writeBlob`/`commit` 据此失败；避免 heads 指向缺失对象。
3. **P1-1 D 类单写者落到同步路径**：`DevicesSpace.write` 校验"文件名 deviceId == 文件内 deviceId"，且"非本机行只允许来自该行所属对端"；同时把 `TreeMergeTest.kt:199-206` 改成真实可构造的跨端形态（否则仍是假保护）。
4. **P1-2 `memory.restore` 的 reason**：要么把 `"memory.restore" to listOf(Param("path"))` 加进 `HighRiskCommandGate.HIGH_RISK`，要么把索引/提示词/指南里的"需 reason"删掉（二选一，别继续脱钩）。
5. **P1-3 1.7 守护扩容**：`PromptGhostReferenceTest.KERNEL_NS` 加 `memory`、`device`（`twin.*` 需插件注册表，另建 shell 侧用例）；`twin-guide.md` 补 `memory.*`/`device.*` 小节并修正 `twin.sync` 描述。
6. **P1-9 `splitBlocks` 语义**：只按 `## HH:mm:ss` / `## {date}` 形态或"文件尾连续块"切分，并补一条"正文含二级标题"的回归用例。
7. **P1-5 GC 作用域**：`gc(ns)` 只清理"本 ns 种子不可达 且 不属于任何 ns 种子/最近窗口"的对象，或提供全库 gc（一次标记全部 ns 的 heads/local/base）。
8. **P1-4 `twin.status` 持久化**：把 `lastSyncAt/lastFilesReceived/lastConflicts/冲突文件数/对象库体积/账本条数/未归纳数` 落到 `{AGENTS}/twin/status.json`（写入时机=同步结束），重启后读回。
9. **P1-8 拆 `MemoryLedger.kt`**（411→<400）：查询/并集段独立成文件。
10. **P1-10 `fromDevice` 消毒**：复用 `sanitizeRelPath` 的字符集，拼文件名前 `take(40)`。

**再修 P2**：死代码清理（`unionMerge`、`ManifestBlob`）→ 原子写统一 → 文案/文档过时 → `schemaVersion` 兼容用例 → `spaces()` 对 `unknown_ns` 静默降级。

**回归口径**：任何 P1 修复后必须复跑本卡三条门禁，并保持 kernel ≥875 / plugin ≥102 且 0 failures；P0 级语义变更（如 base 选择）必须同时补"修复前必红"的用例，本次已按此执行。
