---
name: no-broken-code-push
description: 不可编译的源码禁止推送；APK 构建失败后撤回产物重新上传
metadata:
  type: feedback
---

规则：
1. 推送前确保代码可编译（`./gradlew assembleRelease` 通过）
2. 不可编译的源码不能直接 push
3. APK 构建失败 → 修复 → 重新构建 → 成功后才上传
4. 有问题的产物必须撤回，不能留在 Release 中

**Why:** 2026-07-20 v0.3.0 发布时，源码未编译通过就 push 了，导致多次修复提交。

**How to apply:** 每次改完代码先编译，编译通过再 commit + push。构建 APK 成功后确认产物无误再上传。
