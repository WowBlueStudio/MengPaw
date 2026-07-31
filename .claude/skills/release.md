---
name: release
description: 发布 MengPaw 新版本 — 版本号/CHANGELOG/编译/签名验证/双远端推送/GitHub Release/插件市场/ADB 无线推送 全流程。用户说"发布/发新版/release"时执行。
---

# MengPaw 发布流程（v0.20.0 详细化版）

> 每次发布踩坑都固化于此。严格执行每一步，跳过 = 返工。

## 0. 前置（必须用户明确指示才执行）

- [ ] 用户明确要求发布（记忆 no-release-without-ask）
- [ ] 确认目标版本号（默认当前 +1 minor：如 0.19.7 → 0.20.0；用户指定为准）
- [ ] 确认目标设备无线 ADB 地址（用户提供，端口每次变化）

## 1. 版本号与 CHANGELOG

1. `gradle.properties` → `mengpaw.version=X.Y.Z`（单一事实源；versionCode 由 shell build.gradle.kts 自动算：`Y*1000+Z`，如 0.20.0 → 20000，无需手改）
2. `CHANGELOG.md` 顶部加条目，格式：
   ```
   ## vX.Y.Z (YYYY-MM-DD) — 一句话主题
   ### 新增 / ### 修复 / ### 发行
   ```
   发行节固定含：APK 版本号、plugins.json 变更、测试数
3. `LESSONS.md` 顶部记录本次踩坑（项目惯例，随发布提交）

## 2. 测试与编译

```bash
# 测试（插件单测 + kernel）
./gradlew :plugin-agent-tools:testDebugUnitTest :mengpaw-kernel:test
# 已知预存在失败: AcpProtocolTest round-trip（干净 master 也失败，与本次无关，不阻塞发布）
# 若出现 NEW 失败 → 必须修复才能发布

# Release 构建（clean 防增量脏缓存）
./gradlew clean :mengpaw-shell:assembleRelease
```

## 3. 签名验证（必做，keytool 会误报）

```bash
# keytool 只验 v1 签名，AGP 默认 v1+v2 → keytool 报"不是签名 jar"是误报！
# 必须用 apksigner：
/c/Users/a1138/Android/Sdk/build-tools/35.0.0/apksigner.bat verify --print-certs \
    mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.Y.Z-release.apk
# 期望: Signer #1 certificate DN: CN=MengPaw, OU=Studio, O=WowBlue
# 产物名带版本号: mengpaw-shell-vX.Y.Z-release.apk（不是 mengpaw-shell-release.apk）
```

签名配置：`local.properties` 的 keystore.file/storepass/keypass 缺省时用空密码签（jks 为无密码生成则成功）。签名密钥 = 设备上安装兼容的关键（换机构建必须确认）。

## 4. git 提交 + 双远端推送

```bash
git add -A && git commit -m "release: vX.Y.Z — 主题

Co-Authored-By: Claude <noreply@anthropic.com>"
git tag -a vX.Y.Z -m "vX.Y.Z — 主题"
git push origin master && git push origin vX.Y.Z     # GitHub
git push gitee master && git push gitee vX.Y.Z       # Gitee 镜像
```

- gitee 显示 "Everything up-to-date" 是正常现象（第一次 push 已成功）
- 验证：`git ls-remote gitee master` 与本地 HEAD 一致
- **plugins.json 随 commit 发布**：GitHub Pages 托管的市场索引自动更新（无需单独上传；仅当有插件发布独立 AAR 时才需 gh release 附 AAR）

## 5. GitHub Release

```bash
head -70 CHANGELOG.md > /tmp/release-notes.md    # 只取当前版本章节
gh release create vX.Y.Z \
    "mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.Y.Z-release.apk" \
    --title "MengPaw vX.Y.Z — 主题" --notes-file /tmp/release-notes.md
gh release view vX.Y.Z --json tagName,assets,isDraft   # 验证: assets 含 APK, isDraft=false
```

## 6. ADB 无线推送

```bash
adb connect <平板IP>:<端口>      # 例: 192.168.2.7:42455
adb connect <手机IP>:<端口>      # 例: 192.168.2.9:38999
adb -s <设备> install -r mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.Y.Z-release.apk
adb -s <设备> shell dumpsys package com.mengpaw.shell | grep versionName
```

- 设备上 APK 与本地同密钥签名 → `-r` 覆盖安装成功；签名不匹配（异机构建/Play 安装）→ 报 INSTALL_FAILED_UPDATE_INCOMPATIBLE → 需 `adb uninstall` 后重装（丢数据）
- 端口每次连接可能变化（无线调试需重新配对）——以用户提供为准

## 7. 验证清单（全部通过才算发布完成）

- [ ] `gh release view vX.Y.Z` 存在且 APK 已上传
- [ ] 浏览器可下载 APK（github.com/WowBlueStudio/MengPaw/releases/tag/vX.Y.Z）
- [ ] 两台设备 versionName == X.Y.Z
- [ ] plugins.json 已含新插件条目（GitHub Pages 生效）
- [ ] CHANGELOG 只含当前版本内容

## 严禁

- 编译不过就 push / 不验证签名就上传 / 上传后不验证
- 未经用户指令自动发布
- 跳过确认步骤（LESSONS 103: v0.19.5 空壳 release 教训）
