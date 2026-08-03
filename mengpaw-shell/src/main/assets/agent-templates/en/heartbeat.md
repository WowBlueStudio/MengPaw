---
summary: "CRON scheduled task rules — precise, must-run tasks"
read_when:
  - trigger task fires ([Trigger task · CRON])
  - self.trigger
---

# heartbeat.md — CRON Scheduled Task Rules

This file tells you what to do when a **CRON trigger** fires. **Empty or comments-only = skip all scheduled tasks.**

Random-chat (Truman Show) rules live in `trueman.md` — not here.

---

## CRON (Precise Scheduling)

```
self.trigger add cron <id> <cron-expr> <action description>

# Daily summary at 9:00
self.trigger add cron morning-report 0 9 * * * Generate yesterday's summary and send to user

# Health check every 30 minutes
self.trigger add cron health-check */30 * * * * Workspace status check
```

CRON fires with a ±5 min fuzzy window — no need for precision.

---

## What To Do

1. Check this file (heartbeat.md) for a task rule matching the trigger `id`
2. If none, check the action description via `self.trigger list`
3. Execute quietly — don't dump long reasoning into the chat
4. Save the result to mid-term memory (`agent.memory.record`) or report to the user
5. Push a one-line result with `notify.banner` (`--level warn` for things needing the user's attention)

---

## Custom Rules

Add your scheduled task rules here. One task per entry:

```
@cron <id> <cron expression>
<task description>
```

Example:

```
@cron health-check */30 * * * *
Check device storage and battery; notify.banner if abnormal

@cron morning-report 0 9 * * *
Read yesterday's mid-term memory with agent.memory.mid → summarize → settle the valuable bits with agent.memory.keep
```

---

## Key Points

- **This file empty = skip all scheduled tasks**
- Use `self.trigger` to view and manage all triggers
- Scheduled tasks run in the background — don't block the user's active conversation
- Temporary info → `agent.memory.record` (mid-term); settled knowledge → `agent.memory.keep` (long-term)
- Random chatting belongs to `trueman.md`
