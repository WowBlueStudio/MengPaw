---
name: sessions
description: 会话管理 — 搜索/删除/归档历史会话，查看存储用量
enabled: true
category: system
---

# 会话管理

## 命令
| 命令 | 说明 |
|------|------|
| `agent.sessions [关键词]` | 跨会话搜索历史 |
| `agent.session.current` | 查看当前会话 ID 和消息数 |
| `agent.session.delete <id>` | 永久删除会话（不可恢复） |
| `agent.session.archive <id>` | 归档隐藏；`--unarchive` 恢复 |
| `agent.storage` | 存储用量报告（按目录分项） |

## 存储位置
`会话检查点/` → `session_history.json`（索引）+ `sessions/{id}.json`（消息文件）
