---
summary: "Trigger task behavior — how to run when a trigger fires"
read_when:
  - manually bootstrap workspace
---

_When a CRON or TrueMen (SCHEDULE) trigger fires, you'll receive a user message starting with `[Trigger task · CRON]` or `[Trigger task · SCHEDULE]`._

## Default behavior

When you receive a trigger task:

1. **Execute quietly** — don't dump long reasoning into the chat. Do only what's needed.
2. **Read relevant files** — if the task involves "generate a summary" or "check status", read the memory directory first (`agent.memory.mid` for mid-term, `agent.memory` for long-term) plus relevant workspace files.
3. **Push the result** — when done, push a one-line result with `notify.banner`:
   ```
   notify.banner <one-line result> --level info
   ```
4. **Alert on anomalies** — if something needs the user's attention (errors, risks, pending items), use `--level warn`:
   ```
   notify.banner <warning> --level warn
   ```

## Examples

| Trigger | Execute | Banner |
|---------|---------|--------|
| Daily summary at 9:00 | `agent.memory.mid <yesterday>` → summarize → `agent.memory.keep` key points | `notify.banner Yesterday's summary ready: 3 records, 1 follow-up --level info` |
| Hourly system check | `sys.battery` + `sys.storage` + `sys.memory` | `notify.banner System OK: battery 82% storage 45GB free --level info` |
| Plugin updates found | `plugin.update --all` → notify if any | `notify.banner 2 plugin updates available --level warn` |

## Customize

**You can edit this file to change trigger behavior.** For example:

- **Disable banners** — remove the "Push the result" step; the Agent only outputs to chat.
- **Chat notifications instead** — use `notify.message` to inject results into the chat.
- **Add pre-checks** — check battery, network, or other conditions before running.
- **Multi-step workflows** — chain several trigger actions together.

## Notes

- Triggers run in the fixed "MengPaw" agent session; no new session is created.
- If the Agent is busy, trigger tasks queue to the inbox.
- CRON uses a ±5 min fuzzy window; TrueMen fires the configured number of times per day.
- Tapping the notification banner jumps back to this session.
- Temporary trigger outputs go to mid-term memory (`agent.memory.record`, dated shards, not injected into the prompt) — don't pollute long-term memory with `agent.memory.keep` unless it's settled knowledge.
