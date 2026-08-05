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

> v0.30.0 起：只构建本轮有变更的 APK（浏览器无改动则不重发）。两个都有变更才全量构建：

```bash
# 仅 shell 有变更
./gradlew :mengpaw-shell:assembleRelease
# 仅 browser 有变更
./gradlew :mengpaw-browser:assembleRelease
# 两者都有变更
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
- GitHub 推送后：**Gitee 镜像由 gitee-sync workflow 自动同步**（需已配置 GITEE_TOKEN secret；配置前需手动 `git push gitee master`）——插件索引的国内用户路径依赖它，发布后确认镜像已同步

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

## 6.5 签名验证（v0.20.0 踩坑：keytool 会误报）

产物名**带版本号**：`mengpaw-shell-vX.Y.Z-release.apk`（不是 mengpaw-shell-release.apk）。

```bash
# keytool 只检测 v1 签名，AGP 默认 v1+v2 → keytool 报"不是签名的 jar 文件"是误报！
# 必须用 apksigner：
/c/Users/a1138/Android/Sdk/build-tools/35.0.0/apksigner.bat verify --print-certs \
    mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.Y.Z-release.apk
# 期望输出: Signer #1 certificate DN: CN=MengPaw, OU=Studio, O=WowBlue, ...
```

签名配置：`local.properties` 的 keystore 配置缺省时用空密码签名（jks 为无密码生成则成功）。**签名一致性决定 ADB 能否 -r 覆盖安装**。

## 6.6 插件市场（plugins.json）

- **plugins.json 随 git commit 发布，push 即生效**——客户端 raw 直读双源：GitHub（`raw.githubusercontent.com/WowBlueStudio/MengPaw/master/plugins.json`，全球）/ Gitee（`gitee.com/WowBlueStudio/MengPaw/raw/master/plugins.json`，国内 GeoRouter），**不是 GitHub Pages**。Gitee 侧生效依赖镜像同步（见 §5 注意事项）
- 新插件（如 tools-plugin）加条目（status: builtin）+ 命令清单
- 仅当有插件发布独立 AAR 时才需在 gh release 附 AAR

### 6.6.1 连接器（外置 remote 条目）发布链路

连接器源码在独立仓库 [mengpaw-connectors](https://github.com/WowBlueStudio/mengpaw-connectors)（MIT），发布流程：

1. 连接器仓库构建 AAR → `gh release create plugins-vX.Y.Z` 上传（tag 必须 `plugins-v*`，校验器要求）
2. 主仓库 plugins.json 条目更新：downloadUrl/mirrorUrl → 新 release URL，**checksum（`sha256:64hex`）+ size 必须补齐**，id/version 与连接器 `PluginMetadata` 完全一致（不一致会导致「永远提示更新」）
3. `powershell -File scripts/validate-plugins.ps1` 前后对比 ERROR 数
4. Gitee 镜像生效后把 mirrorUrl 切到 Gitee release URL

## 6.7 测试（发布前）

```bash
./gradlew :plugin-agent-tools:testDebugUnitTest :mengpaw-kernel:test
# 已知预存在失败: AcpProtocolTest round-trip（干净 master 也失败，与本次改动无关，不阻塞发布）
# 出现 NEW 失败 → 必须修复才能发布
```

## 7. ADB 无线推送

```bash
adb connect <平板IP>:<端口>      # 例: 192.168.2.7:42455（端口每次可能变化，以用户提供为准）
adb connect <手机IP>:<端口>      # 例: 192.168.2.9:38999
adb -s <设备> install -r mengpaw-shell/build/outputs/apk/release/mengpaw-shell-vX.Y.Z-release.apk
adb -s <设备> shell dumpsys package com.mengpaw.shell | grep versionName
```

签名不匹配（异机构建/Play 安装）→ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 需 `adb uninstall` 后重装（丢数据）。

## 8. 验证

- [ ] `gh release view vX.Y.Z` 确认 Release 存在且 assets 含 APK、isDraft=false
- [ ] 浏览器打开 `https://github.com/WowBlueStudio/MengPaw/releases/tag/vX.Y.Z`
- [ ] APK 可下载
- [ ] CHANGELOG 只包含当前版本内容
- [ ] 设备 versionName == X.Y.Z
- [ ] `git ls-remote gitee master` 与本地 HEAD 一致（gitee 显示 up-to-date 是正常现象；workflow 生效后自动同步）
- [ ] `curl https://raw.githubusercontent.com/WowBlueStudio/MengPaw/master/plugins.json` 含新条目

---

## 完整命令速查

```bash
# 一键发布（所有步骤通过后执行）
# 注：此命令使用完整 CHANGELOG.md 作为 release notes，
#     建议先用 head -65 提取当前版本内容到临时文件
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
