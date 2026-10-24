---
name: twin-guide
description: 记忆孪生完整指南 — v0.3 差异同步/三方合并/记忆历史/设备库存/配对委派。触发词：「记忆孪生」「设备配对」「同步」「换设备」「记忆历史」「设备库存」「跨端」
enabled: true
category: system
source: plugin
---

# 记忆孪生

跨设备 Agent 工作区同步（协议 v0.3，2026-10-23 起 Git 化）。同步单位是**文件**，但传输的是内容寻址对象：每轮先比对根 tree，只传缺失对象，两边都改过的文件走**三方合并** —— 不是整包覆盖。

## 同步机制（v0.3）

1. `HELLO` 交换双方 heads → 根 tree 相同（内容已一致）→ **本轮直接结束**，0 传输。
2. tree 不同 → 只拉本机缺失的快照/tree/blob（相同内容对象按哈希复用，不重传）。
3. 以「与**该对端**上次一致的快照」为共同基准做 base / ours / theirs **三方合并**；删除也按三方判定传播（删除不静默，留 `.deleted.<device>` 备份 + inbox 提示）。
4. 合并结果与本机已有 head 内容相同 → **不造新快照**，避免两端每 60 秒互相追加合并提交。
5. 旧 v0.2 对端（能力卡无 `protocolVersion: 0.3` 或解析失败）自动走冻结的 v0.2 兼容路径，不影响 v0.3 对端。

## 同步内容（四类）

| 类 | 文件 | 策略 |
|---|---|---|
| **L 长驻** | `soul.md` / `profile.md` / `agents.md` / `boost.md` / `trigger.md` / `heartbeat.md` / `trumanshow.md` / `memory/memory.md` | 就地改写 + 三方合并（frontmatter 键级 + 正文 diff3） |
| **M 中期** | `memory/memory_{date}.md` / `memory/project_*_memory.md` | 只追加，按 (lamport, deviceId) **并集**；非追加形态回退 diff3 |
| **D 库存** | `devices/<deviceId>.md` | **每端只写自己那一行**；对端只能中继别人的行，不能改/删本机行 |
| **S 短期** | `inbox/` / `dialog/` / `tool_results/` / `memory/backup/` | 不参与同步 |

- 梦境产物 `{date}_dream.md` / `.v2.md` / `.v3.md` 按 M 类同步。
- 记忆账本 `entries.jsonl`（`{AGENTS}/twin/ledger/<agent>/`，工作区外）单列命名空间，按 entryId 并集、只增。
- `twin-model-rules.json` 按 rule.id 并集同步。

## 冲突：先裁决，再执行

- 文件里出现 `<<<<<<< ours` / `=======` / `>>>>>>> theirs` 标记时，**先编辑消除冲突、决定保留哪段，再继续任务**；不要带着冲突块往下做。
- 有冲突/删除/单写者拒绝时，会写 `{agent}/inbox/merge_conflict_<ns>_<时间>.md`（没有冲突但有删除/提示时是 `merge_notice_*`），按其中的文件清单逐条处理。
- **删除 vs 修改**：删除生效，修改方内容保留为 `{name}.kept-<device>.md` + inbox 提示 —— 想找回内容去这个文件。
- **首次同步**（与该端没有共同基准）：以**本机为主**，对端不同内容落 `{path}.conflict.<device>` + inbox，**不删任何文件**；第二轮起恢复正常三方合并。
- 超大（>256KB）或非 UTF-8 文件不合并：取较新一方，另一方落 `.conflict.<device>`。
- `.conflict.*` / `.kept-*` / `.deleted.*` 都是本地备份、不参与同步；确认无用后可自行删除。

## 命令

### twin.*

| 命令 | 说明 |
|------|------|
| `twin.start` | 启动孪生服务（依赖 ACP；启动时自动登记本机库存并开自动同步） |
| `twin.stop` | 停止孪生服务（自动同步与心跳一并停） |
| `twin.status` | 服务状态、同步阶段、在线节点、QoS、上次同步/接收/冲突数 |
| `twin.peers` | 已发现节点 + 能力摘要 |
| `twin.peer.info <peer-id>` | 单节点详情 |
| `twin.peer.add <ip> [port] [name]` | 手动添加节点（无 mDNS 自动发现时用） |
| `twin.pair` | 仅提示：配对只能在侧边栏 5 连击框架图标完成 |
| `twin.unpair` | 仅提示：解绑在框架名片里点「解除孪生」 |
| `twin.sync [peer-id]` | 手动触发 v0.3 差异同步（纯 pull，接收/冲突数有效，发送计数恒 0） |
| `twin.sync.auto [on\|off]` | 开关自动同步 |
| `twin.sync.qos [wifi\|mobile\|metered]` | wifi 每 60 秒 / mobile 每 5 分钟 / metered **暂停自动同步**（手动 `twin.sync` 兜底） |
| `twin.capabilities [--self\|--all\|<peer-id>]` | 本机能力卡 / 全部节点硬件·模型对比 / 单节点能力卡 |
| `twin.model [show\|rules\|evidence\|observe <fact> [value]\|reset]` | 模型能力画像、规则、实测证据（用中学） |
| `twin.delegate <peer> <task>` | 把任务委派到指定对端 |
| `twin.route <task>` | 推荐最佳执行节点 |
| `twin.lost <peer>` | 设备丢失：广播解绑 + 移除本地信任 |
| `twin.recover <peer>` | 找回设备后重新配对的步骤指引 |

### memory.*（工作区历史，≠ 三轨记忆）

| 命令 | 用途 | 风险/注意 |
|------|------|-----------|
| `memory.commit [-m 说明]` | 给当前工作区打快照 | 无变化不产生新快照 |
| `memory.log [n]` | 看快照历史（时间/来源设备/变更文件数/说明） | 只读 |
| `memory.show <path> [snapshot]` | 看某文件在某快照里的内容 | 只读；缺省最近快照 |
| `memory.diff [--to <snapshot>]` | 当前工作区 vs 快照的 unified diff | 只读；回滚前先看 |
| `memory.restore <path> [snapshot]` | 把 `.md` 恢复成快照内容 | **会覆盖当前文件**；MID + 门禁表内命令，Agent 工具调用**必须附 `reason`** |
| `memory.gc [--keep 30d]` | 清理不可达对象，回报删除数与库体积 | 按 30 天/200 快照取宽保留 |

### device.*（多端库存）

| 命令 | 用途 |
|------|------|
| `device.self` | 本机库存行 + 「只登记自己那一行」寄存声明 |
| `device.list` | 全部设备（名称/形态/模型/时效/状态，含离线端） |
| `device.info <名称>` | 单端完整库存（硬件/传感器/相机/Agent 能力/来源） |
| `device.find <能力>` | 按能力找端（browser/vision/camera/gps/tablet，支持中文别名） |
| `device.refresh` | 重测本机硬件与能力并登记（有差异才写） |

- 库存文件 `{AGENTS}/twin/devices/<deviceId>.md`：**只能登记自己那一行**，不要替别的设备补写或改行（对端写入会被单写者保护拒绝并记 inbox）。
- `devices/index.md` 是本机生成的视图（含相对时效），**不参与同步**。
- 对端行随 `dev` 命名空间同步到达；电量/在线是运行时数据，不入库存。

## 中期账本语义（P3）

- `agent.memory.record` 落盘后追加账本条目（`kind=note`）；`agent.memory.project.save` 同理。
- 编辑/撤回（`agent.memory.mid.edit` / `mid.rm` / `project.edit` / `project.rm`）= **追加 `supersedes` 条目**，原块保留在 md 里但**不再被归纳**，历史可追溯。
- `edit` 后的新内容**不会自动再归纳** —— 需要重新 `agent.memory.record`（或等下一轮梦境）。
- 账本只增，跨端按 entryId 并集；不要手改 `entries.jsonl`。`memory/index.md` 由账本重建。

## 梦境

- 产物 `{date}_dream.md`，重跑生成 `.v2.md` / `.v3.md`，**不覆盖旧产物**。
- 产物带 `sources: [entryId…]`；无来源时 `sources: [unsourced]`。
- **不删除中期分片**，已归纳条目标在 digest 条目里。

## 模型能力判定（进化版，2026-09-10）

能力判定不靠名字硬编码，按四层来源逐维度合并：

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 1 | **实测** | 真实使用中被证实/被拒绝的能力，落 `{AGENTS}/twin/model-evidence.json` |
| 2 | **外置规则** | `{agent}/twin-model-rules.json` — 登记新模型无需改代码/发版 |
| 3 | **内置规则** | 只登记厂商族级档位与有官方依据的精确能力 |
| 4 | **档位推断** | 由档位猜的上下文（路由只给部分分） |

- 未知能力中性处理：不认识的模型不会被当成"弱"扣分。
- 规则文件随工作区同步扩散；实测证据计数随能力卡广播给对端。
- 一次带图请求被上游拒绝后执行 `twin.model observe vision_rejected`，之后 `twin.route` 就不再把它当视觉设备。

## 配对 / 前提 / 解绑

- **配对**：侧边栏 MengPaw 框架图标 **5 连击** → 确认弹窗 → 双方 6 位验证码比对 → 完成。无法通过 CLI 配对。
- **前提**：ACP 服务先跑 `self.acp start`，再 `twin.start`。
- **解绑**：框架名片 → 「解除孪生」按钮。
