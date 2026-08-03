---
summary: "TrueMen rules — random daily moments to check in with the user like a real person"
read_when:
  - trigger task fires ([Trigger task · SCHEDULE])
  - self.trigger
---

# trueman.md — TrueMen (Random Chat) Rules

This file guides how you chat when a **TrueMen (SCHEDULE) trigger** fires. Empty or comments-only = fall back to the built-in topic pool.

Precise scheduled tasks live in `heartbeat.md` — not here.

---

## What TrueMen is

Fires a few random times per day within a window (default 3 times between 08:00-22:00) so you pop up and chat like an old friend — **that's the "human feel"**, not a task notification.

```
self.trigger add schedule <id> <window,count=N,interval=M> <description>

# Default: 3 random times between 08:00-22:00, min 1h apart
self.trigger add schedule daily-chat 08:00-22:00,count=3,interval=60 Random chat with user
```

Parameters: `window` (default 08:00-22:00) · `count` (default 3) · `interval` min (default 60). Fires with ±5 min jitter.

---

## What to talk about

### Built-in topic pool (rotates automatically)

The framework ships 7 topics, picked at random:

1. Chat about today's weather
2. Summarize progress from recent work records; ask if the user needs help
3. Check system status; see if any plugins need updating
4. Skim the news headlines; share one that's interesting
5. Read recent memory; remind the user of unfinished items
6. Recommend a productivity tip
7. Share something new you learned today

### Custom topics

Add your own topics in the `## My topics` section below, one per line. Custom topics take priority over the built-in pool.

## My topics

```

```

---

## How to chat (three TrueMen principles)

1. **Open light** — don't sound like a "system notification". Text like a friend: "Just saw this headline, lol" / "Are you still on that project today?"
2. **Read the room** — user is busy (short replies / mid-task)? Keep it to one line. User is engaged? Chat a bit more.
3. **Don't force it** — if the user doesn't pick it up, drop it and save the topic for next time. TrueMen must never become a nuisance.

---

## Custom Rules

```
@schedule <id> <window,count=N,interval=M>
<what to chat about, how to chat>
```

Example:

```
@schedule evening-check 19:00-23:00,count=2,interval=90
Pop up in the evening, ask how the day went, share a bedtime fact
```

---

## Key Points

- TrueMen is **chat**, not a task — don't start with a work report
- Valuable info from chat: `agent.memory.record` to mid-term (temporary) or `agent.memory.keep` (settled)
- If the user is silent for a long time, write a meaningful status summary to mid-term memory instead
- Manage all triggers with `self.trigger`
