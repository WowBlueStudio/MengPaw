---
name: twin-guide
description: 记忆孪生完整指南 — 配对/工作区同步/委派/能力判定/设备丢失。触发词：「记忆孪生」「设备配对」「同步」「换设备」
enabled: true
category: system
source: plugin
---

# 记忆孪生

跨设备 Agent 工作区同步 (v0.22.0)。同步整个 `{agent}/` 工作区文档 —
从 soul.md 到 memory/ (长期/中期/项目记忆/梦境产物), 保持跨设备一致。
配对后自动 60 秒周期同步。

## 命令
| 命令 | 说明 |
|------|------|
| `twin.status` | 孪生服务状态、同步阶段、上次同步文件数/冲突数 |
| `twin.peers` | 已发现的对等节点及能力摘要 |
| `twin.sync [peer-id]` | 手动触发工作区同步 (接收/发送/冲突数) |
| `twin.delegate <peer> <task>` | 任务委派到能力更强的对端 |
| `twin.capabilities --all` | 所有节点硬件/模型能力对比 |
| `twin.route <task>` | 系统推荐最佳执行节点 |
| `twin.model` | 本机模型能力画像 + 每项判定的来源 (实测/规则/推断/未知) |
| `twin.model rules` | 生效的能力规则 (工作区外置规则 + 内置族级规则) |
| `twin.model evidence` | 本机模型实测证据清单 |
| `twin.model observe <fact> [value]` | 记录一次真实使用结果 → 判定立即跟着修正 |
| `twin.model reset` | 清空实测证据, 判定回到规则层 |
| `twin.peer.add <ip>` | 手动添加节点（mDNS 不可用时） |
| `twin.lost <peer>` | 设备丢失: 广播解绑 + 移除信任 |

## 同步内容
- **根文档**: soul.md / profile.md / agents.md / boost.md / trigger.md / heartbeat.md / trumanshow.md / {date}_dream.md
- **memory/**: memory.md (长期) / memory_{date}.md (中期) / project_*_memory.md / archive.md
- **模型能力规则**: `twin-model-rules.json` (非 .md 破例同步 — 见下)
- **不同步**: CLI.md / inbox/ / dialog/ / memory/backup/ (本地文件)
- **冲突**: 本地较新且内容不同 → 保存 `.conflict` 备份, 不覆盖

## 模型能力判定（进化版，2026-09-10）

LLM 迭代速度已远超发版节奏, 因此能力判定**不靠名字硬编码**, 而按四层来源合并:

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 1 | **实测** | 真实使用中被证实/被拒绝的能力 (视觉成功、上下文溢出等), 落 `{BASE}/Agent文档/twin/model-evidence.json` |
| 2 | **外置规则** | 工作区文件 `{agent}/twin-model-rules.json` — 登记新模型**无需改代码/发版** |
| 3 | **内置规则** | 只登记厂商族级档位与有官方依据的精确能力 |
| 4 | **档位推断** | 由档位猜的上下文 (路由只给部分分) |

- **未知能力中性处理**: 不认识的模型不会被当作"弱"扣分 (旧实现兜底 BASIC, 新模型永远排在旧型号之后)。
- **一处学到, 全网共享**: 规则文件随工作区同步扩散; 实测证据随能力卡广播给对端。

登记一个新模型 (例):
```json
{ "version": 1, "rules": [
  { "id": "next-flash", "match": "^vendor-flash-2", "quality": "HIGH",
    "vision": true, "ctxTokens": 2000000, "note": "官方 2026-10 发布" }
] }
```
写入 `{agent}/twin-model-rules.json` 即生效 (规则条数/正则长度/文件体积均有上限)。

用中学: 一次带图请求被上游拒绝时执行 `twin.model observe vision_rejected`,
之后 `twin.route` 就不再把它当视觉设备 — 不必等任何人去改代码。

## 配对
侧边栏 MengPaw 框架图标 **5 连击** → 确认弹窗 → 6 位验证码比对 → 配对完成。
无法通过 CLI 配对。

## 前提
ACP 服务需运行：`self.acp start` → `twin.start`

## 解绑
侧边栏框架名片 → "解除孪生"按钮
