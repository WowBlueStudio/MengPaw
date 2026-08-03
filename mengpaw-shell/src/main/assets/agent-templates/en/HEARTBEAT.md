---
summary: "Scheduled task & TrueMen rules — tasks the Agent runs autonomously"
read_when:
  - trigger task fires ([Trigger task · CRON] or [Trigger task · SCHEDULE])
  - self.trigger
---

# HEARTBEAT.md — Scheduled Tasks & TrueMen Rules

This file tells you what to do when a trigger fires. **Empty or comments-only = skip all scheduled tasks.**

---

## Trigger System

MengPaw has two trigger types, managed via `self.trigger`:

### CRON (Precise Scheduling)

```
self.trigger add cron <id> <cron-expr> <action description>

# Daily summary at 9:00
self.trigger add cron morning-report 0 9 * * * Generate yesterday's summary and send to user

# Health check every 30 minutes
self.trigger add cron health-check */30 * * * * Workspace status check
```

### TrueMen (random daily moments)

Fires a few random times per day within a window, so you check in and chat.

```
self.trigger add schedule <id> <window,count=N,interval=M> <description>

# Default: 3 random times between 08:00-22:00, min 1h apart
self.trigger add schedule daily-chat 08:00-22:00,count=3,interval=60 Random chat with user

# Custom: 5 times between 10:00-18:00, min 30min apart
self.trigger add schedule work-check 10:00-18:00,count=5,interval=30 Work progress check
```

Parameters:
| Param | Default | Description |
|-------|---------|-------------|
| window | 08:00-22:00 | Active time range |
| count | 3 | Activations per day |
| interval | 60 | Min interval between activations (min) |

Fires with ±5 min jitter — no need for precision.

---

## What To Do

### When CRON Fires

1. Check this file (HEARTBEAT.md) for a task rule matching the trigger `id`
2. If none, check the action description via `self.trigger list`
3. Run the task
4. Save the result to mid-term memory (`agent.memory.record`) or report to the user

### When TrueMen Fires

1. Check your state with `self.status`
2. If the user was recently active, just do what's natural
3. If the user has been silent for a long time, generate a meaningful status summary into mid-term memory
4. Push important findings with `notify.message`

---

## Custom Rules

Add your scheduled task rules here. One task per entry:

```
@cron <id> <cron expression>
<task description>

@lifetime <id>
<task description>
```

Example:

```
@cron health-check */30 * * * *
Check device storage and battery; notify.banner if abnormal

@lifetime daily-thought
Read today's mid-term memory, distill one interesting observation with agent.memory.keep
```

---

## Key Points

- **This file empty = skip all scheduled tasks**
- Use `self.trigger` to view and manage all triggers
- Use `notify.message` or `notify.banner` to push results to the user
- Scheduled tasks run in the background — don't block the user's active conversation
- Memory: temporary info → `agent.memory.record` (mid-term); settled knowledge → `agent.memory.keep` (long-term)
