# Accessibility Guide

Accessibility services let an app read the screen and simulate actions — the foundation for "automated device control" capabilities. MengPaw will use this in the future for screen-level automation (not used in the current version; this guide explains the concept and how to enable it).

## Enable it

1. System Settings → Accessibility
2. Find the target app (e.g. MengPaw)
3. Toggle the service on and confirm

## Uses and boundaries

- **Read screen**: obtain current UI text and control hierarchy so the Agent understands page state
- **Simulate actions**: tap, swipe, type — cross-app automation (e.g. auto-filling forms)
- **Note**: accessibility can read everything on screen except password fields — a sensitive permission; use only in trusted scenarios

## Relation to MengPaw

- Current Shell capabilities (sys.* / fs.* / net.* etc.) don't need accessibility
- If screen-level control is enabled later, the Agent will add matching commands with security tiers on top of this guide
- Third-party accessibility tools (e.g. auto-clickers) don't conflict with MengPaw
