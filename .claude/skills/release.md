---
name: release
description: 发布 MengPaw 新版本 — 版本号/CHANGELOG/编译/签名验证/双远端推送/GitHub Release/插件市场/ADB 无线推送 全流程。用户说"发布/发新版/release"时执行。
---

> **2026-08-07 已迁移**: 本技能已迁移至 Codex skill `mengpaw-release`(用户级 `~/.codex/skills/mengpaw-release/`),下文为兼容保留,新开发以 Codex skill 为准。

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
3. `docs/lessons.md` 记录本次踩坑（项目惯例，随发布提交；历史教训浓缩库见 §15）

## 2. 崩溃巡检（v0.34.1 事故教训：NSD 竞态从 v0.32.0 存在 4 版未被发现）

```bash
# 发布前对每台已连接设备跑 dropbox 巡检（crash buffer/crash.log 会丢，dropbox 保留全部历史）
adb devices
adb -s <设备> shell dumpsys dropbox --print | grep -A2 -E "^2026|Package:"
# 规则: 本版本号的新 data_app_crash 必须闭环（能解释根因 + 已修复）才能发布；
#       旧版本号的历史崩溃也要扫一眼, 若同一异常多版本连续出现 → 立项修复（本次 NSD 教训）
```

## 2.1 测试与编译

```bash
# 测试（插件单测 + kernel）
./gradlew :plugin-agent-tools:testDebugUnitTest :mengpaw-kernel:test
# 出现任何失败 → 必须修复才能发布（AcpProtocolTest 已于 v0.22.1 修复，不再是"已知预存在失败"）

# Release 构建（clean 防增量脏缓存；v0.30.0 起只构建本轮有变更的 APK）
./gradlew clean :mengpaw-shell:assembleRelease                     # 仅 shell 有变更
./gradlew :mengpaw-browser:assembleRelease                         # 仅 browser 有变更
./gradlew clean :mengpaw-shell:assembleRelease :mengpaw-browser:assembleRelease  # 两者都有变更
```

编译失败速查（完整清单见 docs/lessons.md §15.2）：

| 错误类型 | 检查项 |
|---------|--------|
| `Unresolved reference` | import 是否遗漏 |
| sealed class 字段不一致 | 子类字段名是否统一（content vs finalContent） |
| R8 缺失类 | ProGuard 规则是否加了 `-keep` |
| 跨模块引用失败 | data class 是否在 core 中 |
| `@Composable` 上下文错误 | 函数是否在 `@Composable` 作用域内 |

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
- **plugins.json 随 commit 发布**：push 即生效——客户端 raw 直读双源（GitHub `raw.githubusercontent.com/WowBlueStudio/MengPaw/master/plugins.json` 全球 / Gitee raw 国内），**不是 GitHub Pages**

## 4.1 插件 AAR 发布（独立 tag，仅当插件有变更时）

```bash
# 构建 26 插件 AAR + 回写 plugins.json (checksum/size/changelog)
powershell -ExecutionPolicy Bypass -File scripts/build-plugins.ps1
# 校验 plugins.json 全绿（含 checksum 与 AAR 实际 SHA256 比对）
powershell -ExecutionPolicy Bypass -File scripts/validate-plugins.ps1
# 若改动了 plugins.json 的命令/状态/字段 → 随版本 commit 一并提交
git add plugins.json && git commit -m "chore: 更新 plugins.json（插件 AAR 发布）"
# 独立 tag（与版本 tag 分离；downloadUrl 指向此 tag 的 assets）
git tag plugins-vX.Y.Z && git push origin plugins-vX.Y.Z && git push gitee plugins-vX.Y.Z
# 上传 AAR 到插件 tag（remote 插件的 downloadUrl 指向这些 assets）
gh release create plugins-vX.Y.Z releases/plugins/*.aar \
    --title "Plugin AARs vX.Y.Z" --notes "插件 AAR 批量发布 vX.Y.Z"
```

- 插件 AAR 全流程详见 `.claude/skills/plugin-dev.md`（引用本节 push/gh 操作，不重复）
- 构建脚本模块列表动态派生自 settings.gradle.kts——新增插件模块后无需改脚本

## 4.2 连接器（外置 remote 条目）发布链路

连接器源码在独立仓库 [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)（MIT，JitPack 坐标 `com.github.WowBlueStudio.MengPaw`），主仓库只维护 plugins.json 条目：

1. 连接器仓库构建 AAR → 其仓库 `gh release create plugins-vX.Y.Z` 上传（tag 必须 `plugins-v*`，校验器要求）
2. 主仓库 plugins.json 条目更新：downloadUrl/mirrorUrl → 新 release URL，**checksum（`sha256:64hex`）+ size 必须补齐**，id/version 与连接器 `PluginMetadata` 完全一致（不一致会导致「永远提示更新」）
3. `powershell -File scripts/validate-plugins.ps1` 前后对比 ERROR 数
4. Gitee 镜像生效后把 mirrorUrl 切到 Gitee release URL

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
- **安装弹窗（v0.34.0 修正）**：设备端安装会弹确认框（手机也弹，不再是纯静默），先提示用户点确认再重试，不要盲目重试；荣耀平板每次 install 仍会弹"ICP 警告"（MagicOS 侧载限制，用户已接受，勿再试 pm install/静默方案——荣耀上还有路径转换坑）

## 7. 验证清单（全部通过才算发布完成）

- [ ] `gh release view vX.Y.Z` 存在且 APK 已上传
- [ ] （若发布了插件）`gh release view plugins-vX.Y.Z` 存在且 assets 含全部 AAR
- [ ] 浏览器可下载 APK（github.com/WowBlueStudio/MengPaw/releases/tag/vX.Y.Z）
- [ ] 两台设备 versionName == X.Y.Z
- [ ] `git ls-remote gitee master` 与本地 HEAD 一致（gitee 显示 up-to-date 是正常现象）
- [ ] `curl https://raw.githubusercontent.com/WowBlueStudio/MengPaw/master/plugins.json` 含新条目
- [ ] CHANGELOG 只含当前版本内容

## 严禁

- 编译不过就 push / 不验证签名就上传 / 上传后不验证
- 未经用户指令自动发布
- 跳过确认步骤（LESSONS 103: v0.19.5 空壳 release 教训）
- Release Notes 为空 / 忘记上传 APK —— 用户不知道更新了什么、没有下载链接
