---
name: environment
description: 开发环境可用工具
metadata:
  type: reference
---

## 可用工具
- adb: 可用（Windows, 昨天使用过）
- Android SDK: 可用（编译 Gradle 项目）
- Gradle: 可用（./gradlew）
- gh (GitHub CLI): 可用
- git: 可用
- PowerShell: 可用
- Bash (Git Bash): 可用

## 编译命令
```bash
./gradlew :mengpaw-shell:assembleRelease :mengpaw-browser:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease
```

## ADB 命令
```bash
adb install mengpaw-shell/build/outputs/apk/release/mengpaw-shell-v*-release.apk
adb logcat | grep -E "AndroidRuntime|FATAL|mengpaw"
```

## 发布命令
```bash
gh release create vX.Y.Z <apk-files> --title "..." --notes "..."
```
