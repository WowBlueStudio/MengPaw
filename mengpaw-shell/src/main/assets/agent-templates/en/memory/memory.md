---
summary: "Long-term memory — your workspace cheat-sheet (only settled knowledge goes here)"
read_when:
  - manually bootstrap workspace
---

## What this file is

`memory/memory.md` is your **long-term memory**. It is injected into your system prompt — **you see it on every conversation**. So it only holds important knowledge that stays valid across sessions. Less is more.

Your memory has three tracks, all under `memory/`:

| Track | File | Notes |
|-------|------|-------|
| **Long-term** | `memory.md` (this file) | Injected into the prompt, visible every time; settled knowledge only |
| **Mid-term** | `memory_{date}.md` | Dated shards, NOT injected; temporary/procedural info lives here, dream mode distills it |
| **Project** | `project_{name}_memory.md` | Per-project shards for reusable project experience |

## What to record here

Knowledge that stays valid across sessions, e.g.:

- Key user preferences and habits
- Pitfalls and lessons you've learned (once confirmed, settle them here)
- Long-lived environment config (ports, devices, aliases, common paths)
- Recurring task patterns (what the user often asks for, best practices you found)

## Example

```markdown
### User preferences
- "the doc" means MengPaw-Development-Guide.md
- Always run kernel tests before release

### Lessons
- 305-field data class triggers ART VerifyError crash → use plain class + apply
```

## How to write (use commands, don't edit files directly)

- `agent.memory.keep <content>` — **write long-term memory** (this file); settle important knowledge directly
- `agent.memory.record <content>` — write **mid-term memory** (dated shard); temporary info, conversation summaries, progress
- `agent.memory` — view long-term; `agent.memory.mid` — view mid-term; `agent.memory.project` — view project
- Editing files directly does not invalidate the prompt cache — the Agent may see stale content

## What NOT to record

- **Conversation details, temporary progress** → mid-term (`agent.memory.record`); dream mode distills it
- **Per-turn dynamic info** → keep out (prompt bloat + prefix cache misses)
- **Command usage already in agents.md** → don't duplicate
- **Memory Twin syncs this directory** — anything written here propagates to other devices, mind your words

---

_This file is your memory starting point. After a while, reshape it into your own style._
