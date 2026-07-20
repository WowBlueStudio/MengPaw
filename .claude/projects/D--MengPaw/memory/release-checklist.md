---
name: release-checklist
description: 版本发布必须包含 APK + CHANGELOG
metadata:
  type: feedback
---

每次发布新版本必须同时上传：
1. CHANGELOG.md 已更新
2. Shell APK (`mengpaw-shell-release.apk`)
3. Browser APK (`mengpaw-browser-release.apk`)
4. git tag 推送

构建命令：
```bash
./gradlew :mengpaw-shell:assembleRelease :mengpaw-browser:assembleRelease
```

APK 位置：
- `mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.X.X-release.apk`
- `mengpaw-browser/build/outputs/apk/release/mengpaw-browser-vX.X.X-release.apk`

上传：
```bash
gh release create vX.X.X *.apk --title "MengPaw vX.X.X" --notes-file CHANGELOG.md
```

**Why:** 2026-07-20 用户明确要求每次发布带 APK 和更新日志。

**How to apply:** 用户说"发布新版本"后，先确保 CHANGELOG 已更新，构建 APK，上传到 GitHub Release。
