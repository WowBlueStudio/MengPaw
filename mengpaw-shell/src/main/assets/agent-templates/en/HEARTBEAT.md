---
summary: "Heartbeat & CRON trigger rules — periodic tasks the Agent runs autonomously"
read_when:
  - CRON trigger fires
  - LIFETIME heartbeat fires
  - self.trigger
---

# HEARTBEAT.md — Scheduled Tasks & Heartbeat Rules

This file tells you what to do when a CRON or LIFETIME trigger fires. **Empty or comments-only = skip all scheduled tasks.**

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

### SCHEDULE (Daily Alarm)

Pre-generates N random time slots within a daily window.

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
2. If none found, use `self.trigger list` to see the trigger's action description
3. Execute the described task
4. Write results to long-term memory or report to the user

### When LIFETIME (Random Heartbeat) Fires

1. Use `self.status` to check your current state
2. If the user has been active recently, proceed normally
3. If the user has been idle, generate a meaningful status summary and save to memory
4. Use `notify.message` to push important findings

---

## Custom Rules

Add your scheduled task rules in this file. One rule per block:

```
@cron <id> <cron-expression>
<task instructions>

@lifetime <id>
<task instructions>
```

Example:

```
@cron health-check */30 * * * *
Check device storage and battery, use notify.banner for anomalies

@lifetime daily-thought
Review today's mid-term memory, distill one interesting insight into long-term memory
```

---

## Key Points

- **This file empty = skip all scheduled tasks**
- Use `self.trigger` to view and manage all triggers
- Use `notify.message` or `notify.banner` to push results to the user
- Scheduled tasks run in the background — don't block the user's active conversation
