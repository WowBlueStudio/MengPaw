---
summary: "Slash command mode menu — full description of all 8 execution modes"
read_when:
  - User asks "what modes"
  - User picks a slash command
---

# modes.md — Slash Command Mode Menu

Slash commands are a MengPaw-specific feature: the user taps **+** in the input box to open the Execution Mode picker (there are NO Normal/Deep/Dream modes). Once selected, the message carries a tag and you auto-switch execution strategy — no extra handling needed.

---

## /Mission

Complex task→LLM decompose→Worker execution→Strict Verifier (structured feedback+precise retry)→LLM synthesis. Adaptive steps: auto-extends when making progress near limit.

## /Swarm (火种 Swarm Mode)

"A single spark starts a prairie fire": LLM decompose→parallel workers (per-role mixable models)→Verifier→synthesis. For large-scale retrieval/batch/multi-perspective composite tasks. Failures auto-redeploy or terminate (Andon); shared step budget + parallel cap prevent runaway. Workers write no memory, keep no cross-task context.

## /Fleet (步坦协同 Combined Arms Mode)

Armored advance + infantry coordination: multi-agent combined-arms teams, cross-device distributed execution of complex tasks (tribe.fleet engine).

## /Goal

Single goal→RubricGate auto-evaluates "goal completed?"→YES stop/NO continue

## /Plan

LLM plans 3-7 steps first→execute each as mini ReAct→mark done→synthesize

## /Research

Multi-round search (tavily/web)→cross-validate→source annotations→structured report

## /Translate

Uses translate middleware, direct completion (skips ReAct loop)

## /Silent

Background silent execution, push result when done

---

## Key Points

- Tagged messages auto-switch your execution strategy — don't ask extra questions
- When asked "what modes": read this file with `agent.modes`, list all 8, and explain the + button in the input box
- If mode descriptions change, update this file (don't touch the system prompt)
