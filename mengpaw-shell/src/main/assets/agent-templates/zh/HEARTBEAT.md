---
summary: "定时任务与随机对话规则 — Agent 定期主动执行的任务"
read_when:
  - 触发器任务触发（[触发器任务 · CRON] 或 [触发器任务 · SCHEDULE]）
  - self.trigger
---

# HEARTBEAT.md — 定时任务与随机对话规则

此文件指导你在触发器命中时应该做什么。**留空或只有注释 = 跳过所有定时任务。**

---

## 触发器系统

MengPaw 有两种触发器，通过 `self.trigger` 管理：

### CRON 触发器（精确定时）

```
self.trigger add cron <id> <cron-expr> <action描述>

# 每天早上 9:00 生成昨日摘要
self.trigger add cron morning-report 0 9 * * * 生成昨日摘要发送给用户

# 每 30 分钟检查一次工作区
self.trigger add cron health-check */30 * * * * 工作区状态检查
```

### 随机对话（每日随机时段）

每天在指定时段内随机出现 N 次，跟你聊两句。

```
self.trigger add schedule <id> <窗口,count=N,interval=M> <action描述>

# 默认: 08:00-22:00 之间随机 3 次，最小间隔 1 小时
self.trigger add schedule daily-chat 08:00-22:00,count=3,interval=60 和用户随机闲聊

# 自定义: 10:00-18:00 之间 5 次，间隔至少 30 分钟
self.trigger add schedule work-check 10:00-18:00,count=5,interval=30 工作进度检查
```

参数说明：
| 参数 | 默认值 | 说明 |
|------|--------|------|
| 窗口 | 08:00-22:00 | 触发时间范围 |
| count | 3 | 每日触发次数 |
| interval | 60 | 最小触发间隔（分钟） |

触发时有 ±5 分钟抖动，不需精确到秒。

---

## 你该怎么做

### 当 CRON 触发时

1. 检查此文件（HEARTBEAT.md）中是否有对应 `id` 的任务说明
2. 如果没有，用 `self.trigger list` 查看触发的 action 描述
3. 执行 action 中的任务
4. 结果写入中期记忆（`agent.memory.record`）或汇报给用户

### 当随机对话触发时

1. 用 `self.status` 检查当前状态
2. 如果用户最近有活跃对话，直接做该做的事
3. 如果用户长时间未响应，生成有意义的状态摘要存到中期记忆
4. 用 `notify.message` 推送重要发现

---

## 自定义规则

在此文件中添加你的定时任务规则。每行一个任务：

```
@cron <id> <cron表达式>
<任务说明>

@lifetime <id>
<任务说明>
```

示例：

```
@cron health-check */30 * * * *
检查设备存储空间和电池状态，异常时 notify.banner

@lifetime daily-thought
翻看今天的中期记忆，提炼一条有趣的观察用 agent.memory.keep 存为长期记忆
```

---

## 要点

- **此文件留空 = 跳过所有定时任务**
- 用 `self.trigger` 查看和管理所有触发器
- 使用 `notify.message` 或 `notify.banner` 向用户推送结果
- 定时任务是后台执行的，不要阻塞用户当前对话
- 记忆相关操作：临时信息走 `agent.memory.record`（中期），沉淀知识才用 `agent.memory.keep`（长期）
