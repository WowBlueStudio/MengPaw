---
name: twin-guide
description: 记忆孪生完整指南 — 配对/同步/委派/账本审计
enabled: true
category: system
---

# 记忆孪生

跨设备 Agent 记忆同步。配对后自动 60 秒周期同步。

## 命令
| 命令 | 说明 |
|------|------|
| `twin.status` | 孪生服务状态、同步阶段、账本条目、链完整性 |
| `twin.peers` | 已发现的对等节点及能力摘要 |
| `twin.sync [peer-id]` | 手动触发全量同步 |
| `twin.delegate <peer> <task>` | 任务委派到能力更强的对端 |
| `twin.capabilities --all` | 所有节点硬件/模型能力对比 |
| `twin.route <task>` | 系统推荐最佳执行节点 |
| `twin.ledger.verify` | 验证记忆链完整性 |
| `twin.ledger.stats` | 查看账本统计和来源分布 |
| `twin.peer.add <ip>` | 手动添加节点（mDNS 不可用时） |

## 配对
侧边栏 MengPaw 框架图标 **5 连击** → 确认弹窗 → 6 位验证码比对 → 配对完成。
无法通过 CLI 配对。

## 前提
ACP 服务需运行：`self.acp start` → `twin.start`

## 解绑
侧边栏框架名片 → "解除孪生"按钮
