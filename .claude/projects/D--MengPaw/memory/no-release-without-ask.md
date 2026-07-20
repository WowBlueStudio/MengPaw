---
name: no-release-without-ask
description: 禁止未经用户明确指令的版本发布
metadata:
  type: feedback
---

版本发布（包括 git tag、GitHub Release、APK 上传等）必须等待用户明确说"发布新版本"或等效指令。严禁在 bug 修复或其他任务完成后自动发布版本。

**Why:** 2026-07-19 曾发生过未经用户许可就直接发布的情况，用户明确表示"这个不行"。

**How to apply:** 任何发布相关操作（commit message 含 "release" / `gh release create` / tag push / APK 上传）前必须得到用户明确的"发布"指令。修复 bug 后只做本地 commit，不自动发布。
