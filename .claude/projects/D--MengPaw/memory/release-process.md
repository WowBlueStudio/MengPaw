---
name: release-process
description: MengPaw 完整发布流程：版本号更新→编译验证→模拟器测试→构建APK→上传GitHub+Gitee
metadata:
  type: reference
---

# MengPaw 发布流程

## 步骤总览

```
1. 版本号更新 (build.gradle.kts)
2. 编译验证 (compileDebugKotlin)
3. R8 Release 构建 (assembleRelease)
4. 模拟器验证 (logcat 闪退检测)
5. git commit + tag
6. GitHub Release + APK 上传
7. Gitee push
```

## 1. 版本号更新

文件: `mengpaw-shell/build.gradle.kts` + `mengpaw-browser/build.gradle.kts`
```kotlin
versionCode = N+1          // Shell: 目前9, Browser: 目前5
versionName = "X.Y.Z"      // 语义化版本
```

## 2. 编译验证

```bash
# Debug 编译 (快速失败)
./gradlew :mengpaw-shell:compileDebugKotlin :mengpaw-browser:compileDebugKotlin

# 修复编译错误后重新验证
# 常见修复: 移除损坏的 Replace 产物, 添加缺失 import, 修复 @Composable 上下文错误
```

## 3. R8 Release 构建

```bash
./gradlew :mengpaw-shell:assembleRelease :mengpaw-browser:assembleRelease

# 如果 R8 报 missing classes:
# 添加 -dontwarn 规则到 proguard-rules.pro
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
```

## 4. 模拟器验证

```bash
# 启动模拟器 (无窗口模式)
$ANDROID_SDK/emulator/emulator.exe -avd MengPaw_Test -no-window -no-audio &
# 等待 boot 完成
adb shell getprop sys.boot_completed  # 返回 "1" = 就绪

# 安装 APK
adb install -r mengpaw-shell-vX.X.X-release.apk
adb install -r mengpaw-browser-vX.X.X-release.apk

# 启动并监控闪退
adb logcat -c
adb shell am start -n "com.mengpaw.shell/.MainActivity"
sleep 15
adb logcat -d -s AndroidRuntime:E  # 应为空

adb shell am start -n "com.mengpaw.browser/.BrowserActivity"
sleep 15
adb logcat -d -s AndroidRuntime:E  # 应为空

# 冒烟测试
adb shell am start -a android.intent.action.VIEW -d "https://www.baidu.com" com.mengpaw.browser
adb shell am start -n "com.mengpaw.shell/.MainActivity"

# 验证进程存活
adb shell pidof com.mengpaw.shell  # 应输出 PID
adb shell pidof com.mengpaw.browser  # 应输出 PID
```

## 5. 提交 + 标签

```bash
git add -A
git commit -m "release: vX.X.X — 一句话总结"

git tag vX.X.X
git push origin master --tags
git push gitee master --tags
```

## 6. GitHub Release

```bash
# 创建 release 并上传 APK
gh release create vX.X.X \
  mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.X.X-release.apk \
  mengpaw-browser/build/outputs/apk/release/mengpaw-browser-vX.X.X-release.apk \
  --title "MengPaw vX.X.X — 简短标题" \
  --notes-file CHANGELOG.md
```

## 7. Gitee

Gitee Release 通过网页手动创建 (gh CLI 不支持 Gitee)，或通过 git push 推送 tag 后手动上传 APK。

---

## 记忆链接
- [[release-checklist]] — 发布必须带 APK + CHANGELOG
- [[no-release-without-ask]] — 未经用户指令不发布
- [[bug-audit-methodology]] — 发布前审计清单
