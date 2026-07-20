# MengPaw 发布流程

> 固化于 2026-07-20 v0.3.0 发布后。吸取 5 次编译失败、ProGuard 缺失、import 遗漏等教训。

---

## 0. 前置条件

- [ ] 所有代码修改已完成
- [ ] `git status` 干净（没有未提交的修改）
- [ ] 已确认版本号（Shell `build.gradle.kts` + Browser `build.gradle.kts`）

---

## 1. 编译验证

```bash
# 完整编译（必须通过才继续）
./gradlew clean assembleRelease
```

### 1.1 如果编译失败

- 看错误定位 → 修复 → 回到步骤 1
- **绝不跳过编译直接 push**

### 1.2 常见编译问题速查

| 错误类型 | 检查项 |
|---------|--------|
| `Unresolved reference` | import 是否遗漏 |
| `Missing '}'` | 括号是否配对，编辑是否破坏了结构 |
| sealed class 字段不一致 | 子类字段名是否统一（content vs finalContent） |
| R8 缺失类 | ProGuard 规则是否加了 `-keep` |
| 跨模块引用失败 | data class 是否在 core 中 |
| companion object 重复 | 同文件是否有多处 companion object |
| `@Composable` 上下文错误 | 函数是否在 `@Composable` 作用域内 |

---

## 2. 更新 CHANGELOG

```bash
# 编辑 CHANGELOG.md，在文件顶部添加新版本条目
# 格式：
## vX.Y.Z (YYYY-MM-DD) — 模块名
### 新增
### 修复
### 发行
```

---

## 3. 更新版本号

```bash
# mengpaw-shell/build.gradle.kts
versionCode = N+1
versionName = "X.Y.Z"

# mengpaw-browser/build.gradle.kts（如有变更）
versionCode = N+1
versionName = "X.Y.Z"
```

---

## 4. 构建 APK

```bash
./gradlew :mengpaw-shell:assembleRelease :mengpaw-browser:assembleRelease
```

验证产物：
```bash
ls -la mengpaw-shell/build/outputs/apk/release/*.apk
ls -la mengpaw-browser/build/outputs/apk/release/*.apk
```

---

## 5. 提交 + 打标签 + 推送

```bash
git add -A
git commit -m "release: vX.Y.Z — 简要描述"
git tag -a vX.Y.Z -m "vX.Y.Z — 简要描述"
git push origin master
git push origin vX.Y.Z
```

**注意事项**：
- commit message 以 `release:` 开头
- tag 名称与版本号一致（`vX.Y.Z`）
- 确认 GitHub + Gitee 两个 remote 都推送成功

---

## 6. 上传 GitHub Release

```bash
# 提取当前版本 CHANGELOG（只取顶部章节，不含历史版本）
head -65 CHANGELOG.md > /tmp/release-notes.md

# 创建 Release 并上传 APK
gh release create vX.Y.Z \
    mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.Y.Z-*.apk \
    mengpaw-browser/build/outputs/apk/release/mengpaw-browser-vX.Y.Z-*.apk \
    --title "MengPaw vX.Y.Z — 简要描述" \
    --notes-file /tmp/release-notes.md
```

---

## 7. 验证

- [ ] `gh release view vX.Y.Z` 确认 Release 存在
- [ ] 浏览器打开 `https://github.com/WowBlueStudio/MengPaw/releases/tag/vX.Y.Z`
- [ ] APK 可下载
- [ ] CHANGELOG 只包含当前版本内容

---

## 完整命令速查

```bash
# 一键发布（所有步骤通过后执行）
VERSION="0.3.0"
./gradlew clean :mengpaw-shell:assembleRelease :mengpaw-browser:assembleRelease && \
git add -A && git commit -m "release: v$VERSION" && \
git tag -a v$VERSION -m "v$VERSION" && \
git push origin master && git push origin v$VERSION && \
gh release create v$VERSION \
    mengpaw-shell/build/outputs/apk/release/mengpaw-shell-v$VERSION-*.apk \
    mengpaw-browser/build/outputs/apk/release/mengpaw-browser-v*.apk \
    --title "MengPaw v$VERSION" \
    --notes-file CHANGELOG.md
```

---

## 严禁行为

| 禁止 | 原因 |
|------|------|
| 编译不过就 push | 源码无法构建 APK |
| 不验证 APK 就上传 | 产物可能损坏 |
| Release Notes 为空 | 用户不知道更新了什么 |
| 忘记上传 APK | 用户没有下载链接 |
| 未经用户指令自动发布 | 用户明确要求控制发布节奏 |
| 上传后不验证 | 可能上传失败而不自知 |
