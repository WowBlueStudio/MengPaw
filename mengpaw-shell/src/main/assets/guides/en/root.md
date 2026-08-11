# Root Guide

Root gives the Agent the highest device privilege (`su`), enabling operations normal apps can't do: system file read/write, app freeze/uninstall, system property changes, backup/restore. MengPaw's built-in root-plugin provides these capabilities.

## Prerequisites

- Device is unlocked and rooted (Magisk / KernelSU etc.); `root.status` detects the current state
- Install root-plugin: plugin market → search `root` → install → activate
- Every root action is written to the audit log (`root.audit`); high-risk actions prompt for confirmation each time

## Common commands

- `root.status` — detect root environment and su availability
- `root.exec <command>` — run a single command as root (output truncated at 4000 chars; full output in `/sdcard/root_out.txt`)
- `root.apps.list` / `root.apps.freeze <package>` — app list / freeze (try freeze before uninstall)
- `root.fs.cat/write` — view and write system files
- `root.system.hosts` — manage hosts (ad/domain blocking)
- `root.backup.save/restore` — app data backup and restore

## Risks

- Root lowers the security boundary: a malicious app or prompt-injection gaining root is severe — keep the security gates enabled
- Freezing system apps can break the device; freeze user apps first and unfreeze immediately if something goes wrong
- Back up before modifying `/system`; some OEM devices (Honor/vivo etc.) restrict su — failure is normal
- Most MengPaw features work without root; enable it only when you truly need system-level access
