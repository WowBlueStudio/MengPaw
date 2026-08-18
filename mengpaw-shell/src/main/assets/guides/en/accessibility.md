# Accessibility Guide

Accessibility services let an app read the screen and simulate actions — the foundation for "automated device control" capabilities. MengPaw now includes an accessibility service (v0.42.2): the Agent can drive screen-level automation through the `sys.accessibility.*` commands.

## Enable it

1. System Settings → Accessibility
2. Find the target app (e.g. MengPaw)
3. Toggle the service on and confirm

## Uses and boundaries

- **Read screen**: obtain current UI text and control hierarchy so the Agent understands page state (`sys.accessibility.dump`)
- **Simulate actions**: tap, swipe, type, back/home/recents — cross-app automation (e.g. auto-filling forms)
- **Note**: accessibility can read everything on screen except password fields — a sensitive permission; use only in trusted scenarios

## Relation to MengPaw

- **Enable**: Settings → Accessibility → MengPaw → toggle the service on and confirm; then verify with `sys.accessibility.status`
- **Commands**: `sys.accessibility.dump` (screen read, MID risk) | `click` / `swipe` / `input` / `back` / `home` / `recents` (simulated actions, HIGH risk — confirmation required)
- **Security tiers**: simulated actions are treated like the user tapping the screen — all HIGH risk with a confirmation dialog per execution; screen reads are MID
- **Coexistence**: existing Shell command-level capabilities (sys.* / fs.* / net.* etc.) don't need accessibility
- Third-party accessibility tools (e.g. auto-clickers) don't conflict with MengPaw
