---
name: twin-guide
description: 记忆孪生完整指南 — 配对/工作区同步/委派/设备丢失。触发词：「记忆孪生」「设备配对」「同步」「换设备」
enabled: true
category: system
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
| `twin.peer.add <ip>` | 手动添加节点（mDNS 不可用时） |
| `twin.lost <peer>` | 设备丢失: 广播解绑 + 移除信任 |

## 同步内容
- **根文档**: soul.md / profile.md / agents.md / boost.md / trigger.md / HEARTBEAT.md / {date}_dream.md
- **memory/**: memory.md (长期) / memory_{date}.md (中期) / project_*_memory.md / archive.md
- **不同步**: CLI.md / inbox/ / dialog/ / memory/backup/ (本地文件)
- **冲突**: 本地较新且内容不同 → 保存 `.conflict` 备份, 不覆盖

## 配对
侧边栏 MengPaw 框架图标 **5 连击** → 确认弹窗 → 6 位验证码比对 → 配对完成。
无法通过 CLI 配对。

## 前提
ACP 服务需运行：`self.acp start` → `twin.start`

## 解绑
侧边栏框架名片 → "解除孪生"按钮
