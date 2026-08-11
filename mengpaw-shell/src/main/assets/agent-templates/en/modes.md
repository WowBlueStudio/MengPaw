---
summary: "Slash command mode menu — full description of all 7 execution modes"
read_when:
  - User asks "what modes"
  - User picks a slash command
---

# modes.md — Slash Command Mode Menu

Slash commands are a MengPaw-specific feature: the user taps **+** in the input box to open the Execution Mode picker (there are NO Normal/Deep/Dream modes). Once selected, the message carries a tag and you auto-switch execution strategy — no extra handling needed.

## /Swarm (火种 Swarm Mode)

Swarm is the evolved Mission: it inherits Mission's decompose→parallel workers→verifier→synthesis pipeline with the downgrade-pass semantics, and evolves per-role mixed models, the Andon failure protocol (redeploy/terminate instead of silent retry), and a shared step budget to prevent runaway. "A single spark starts a prairie fire": LLM decompose→parallel workers (per-role mixable models)→Verifier→synthesis. For large-scale retrieval/batch/multi-perspective composite tasks. Workers write no memory, keep no cross-task context.

## /Fleet (步坦协同 Combined Arms Mode)

Armored advance + infantry coordination: multi-agent combined-arms teams, cross-device distributed execution of complex tasks (tribe.fleet engine).

## /Goal

Single goal→RubricGate auto-evaluates "goal completed?"→YES stop/NO continue

## /Plan

LLM plans 3-7 steps first→execute each as mini ReAct→mark done→synthesize

## /Research

Multi-round search (tavily/web)→cross-validate→source annotations→structured report

## /Translate

Uses the translation middleware (source language auto-detected) and translates directly (no ReAct loop). You may specify the target language in the task (e.g. "translate to English"); when omitted, defaults to English for Chinese input or Chinese for English input. Falls back to a single LLM call when the middleware is unavailable. The result appears as an Agent message.

## /Silent

Background silent execution, push result when done

---

## Key Points

- Tagged messages auto-switch your execution strategy — don't ask extra questions
- When asked "what modes": read this file with `agent.modes`, list all 7, and explain the + button in the input box
- If mode descriptions change, update this file (don't touch the system prompt)
